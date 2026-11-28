package com.shopcopilot.business.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopcopilot.business.mq.DomainEvents.OrderStatusChangedEvent;
import com.shopcopilot.business.mq.DomainEvents.ProductChangedEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * RocketMQ 领域事件发布器：
 * 1. 严格在数据库事务 afterCommit 后发送，杜绝事务回滚却发出幽灵消息；
 * 2. MQ 不可用时 fail-open 记日志，不回滚已提交的核心交易。
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final MqProperties props;
    private final ObjectMapper objectMapper;
    private volatile DefaultMQProducer producer;

    public DomainEventPublisher(MqProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            log.info("RocketMQ publisher disabled (app.mq.enabled=false)");
            return;
        }
        try {
            DefaultMQProducer p = new DefaultMQProducer(props.getProducerGroup());
            p.setNamesrvAddr(props.getNamesrvAddr());
            p.setSendMsgTimeout(3000);
            p.setRetryTimesWhenSendFailed(2);
            p.start();
            this.producer = p;
            log.info("RocketMQ producer started: group={} namesrv={}",
                props.getProducerGroup(), props.getNamesrvAddr());
        } catch (Exception ex) {
            log.warn("RocketMQ producer startup failed (fail-open): {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        DefaultMQProducer p = this.producer;
        if (p != null) {
            p.shutdown();
            this.producer = null;
        }
    }

    public void publishOrderStatusChanged(OrderStatusChangedEvent event) {
        runAfterCommit(() -> sendJson(
            props.getOrderTopic(),
            "ORDER_" + event.toStatus(),
            event.eventId(),
            event
        ));
    }

    public void publishProductChanged(ProductChangedEvent event) {
        runAfterCommit(() -> sendJson(
            props.getProductTopic(),
            event.action(),
            event.eventId(),
            event
        ));
    }

    void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    void sendJson(String topic, String tag, String eventId, Object payload) {
        if (!props.isEnabled()) {
            return;
        }
        DefaultMQProducer p = this.producer;
        if (p == null) {
            log.warn("RocketMQ producer not ready, skipping event={} topic={}", eventId, topic);
            return;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(payload);
            Message msg = new Message(topic, tag, eventId, body);
            SendResult result = p.send(msg, 3000);
            log.info("RocketMQ sent: topic={} tag={} key={} msgId={} status={}",
                topic, tag, eventId, result.getMsgId(), result.getSendStatus());
        } catch (Exception ex) {
            log.error("RocketMQ send failed (fail-open): topic={} key={} err={}",
                topic, eventId, ex.getMessage());
        }
    }

    // 供单测注入模拟 Producer
    void setProducerForTest(DefaultMQProducer producer) {
        this.producer = producer;
    }

    static String decodeBody(Message msg) {
        return new String(msg.getBody(), StandardCharsets.UTF_8);
    }
}
