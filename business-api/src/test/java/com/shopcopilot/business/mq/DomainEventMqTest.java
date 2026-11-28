package com.shopcopilot.business.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopcopilot.business.mq.DomainEvents.OrderStatusChangedEvent;
import com.shopcopilot.business.mq.DomainEvents.ProductChangedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

class DomainEventMqTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void cleanupTransactionSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void should_deferPublishUntilAfterCommit_andNotSendOnRollback() throws Exception {
        MqProperties props = new MqProperties();
        props.setEnabled(true);
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        sendResult.setMsgId("msg-001");
        when(producer.send(any(Message.class), anyLong())).thenReturn(sendResult);

        DomainEventPublisher publisher = new DomainEventPublisher(props, objectMapper);
        publisher.setProducerForTest(producer);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
            "evt-ord-1", "SO202611240002", 10001L, 2L, "智能手表 S2",
            1, 2, "已发货", 89900L, "2026-11-28T11:30:00Z");
        publisher.publishOrderStatusChanged(event);

        // 事务提交前绝不发送消息
        verify(producer, never()).send(any(Message.class), anyLong());

        // 模拟事务提交触发 afterCommit
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(1)).send(captor.capture(), eq(3000L));
        Message sent = captor.getValue();
        assertEquals(props.getOrderTopic(), sent.getTopic());
        assertEquals("evt-ord-1", sent.getKeys());
        assertTrue(DomainEventPublisher.decodeBody(sent).contains("SO202611240002"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_dispatchOnceWithRedisDedup_andRequestReconsumeOnDownstreamError() throws Exception {
        MqProperties props = new MqProperties();
        props.setEnabled(true);
        props.setAgentRuntimeBaseUrl("http://agent-runtime.local:8000");

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentEventDispatcher dispatcher = new AgentEventDispatcher(
            props, redisTemplate, objectMapper, builder.build());

        ProductChangedEvent evt = new ProductChangedEvent(
            "evt-prd-9", 9L, "UPDATED", "香薰蜡烛礼盒", "家居",
            "天然大豆蜡，雪松与白茶香型", 15900L, 70, 1, "2026-11-28T11:30:00Z");
        MessageExt msg = new MessageExt();
        msg.setTopic(props.getProductTopic());
        msg.setKeys(evt.eventId());
        msg.setBody(objectMapper.writeValueAsBytes(evt));

        // 首次消费：占位成功并转发至 Python，返回 CONSUME_SUCCESS
        when(valueOps.setIfAbsent(eq("mq:consumed:evt-prd-9"), eq("1"), any(Duration.class)))
            .thenReturn(true, false);
        server.expect(MockRestRequestMatchers.requestTo("http://agent-runtime.local:8000/api/internal/events/product-sync"))
            .andRespond(MockRestResponseCreators.withSuccess("{\"accepted\":true}", MediaType.APPLICATION_JSON));

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, dispatcher.handleSingleMessage(msg));
        // 重复投递：Redis 去重拦截，不再调下游 HTTP
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, dispatcher.handleSingleMessage(msg));
        server.verify();

        // 下游 500 故障：释放去重键并返回 RECONSUME_LATER 触发 RocketMQ 重试
        MessageExt failMsg = new MessageExt();
        failMsg.setTopic(props.getOrderTopic());
        failMsg.setKeys("evt-ord-err");
        failMsg.setBody("{\"eventId\":\"evt-ord-err\",\"orderNo\":\"SO1\"}".getBytes(StandardCharsets.UTF_8));
        when(valueOps.setIfAbsent(eq("mq:consumed:evt-ord-err"), eq("1"), any(Duration.class)))
            .thenReturn(true);
        server.reset();
        server.expect(MockRestRequestMatchers.requestTo("http://agent-runtime.local:8000/api/internal/events/order-status"))
            .andRespond(MockRestResponseCreators.withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, dispatcher.handleSingleMessage(failMsg));
        verify(redisTemplate).delete("mq:consumed:evt-ord-err");
    }
}
