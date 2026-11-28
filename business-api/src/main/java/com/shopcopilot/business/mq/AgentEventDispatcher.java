package com.shopcopilot.business.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RocketMQ 消费转发器：
 * 订阅订单状态变更与商品变更 Topic，基于 Redis SET NX 做 eventId 幂等去重后，
 * 异步投递给 Python agent-runtime 触发主动播报或单条商品增量向量索引；
 * 下游异常时释放去重键并返回 RECONSUME_LATER，交由 RocketMQ 重试队列与死信兜底。
 */
@Component
public class AgentEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentEventDispatcher.class);
    private static final String DEDUP_PREFIX = "mq:consumed:";

    private final MqProperties props;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile DefaultMQPushConsumer consumer;

    public AgentEventDispatcher(
            MqProperties props,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this(props, redisTemplate, objectMapper, RestClient.builder().build());
    }

    AgentEventDispatcher(
            MqProperties props,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RestClient restClient) {
        this.props = props;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            log.info("RocketMQ consumer disabled (app.mq.enabled=false)");
            return;
        }
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getConsumerGroup());
            c.setNamesrvAddr(props.getNamesrvAddr());
            c.subscribe(props.getOrderTopic(), "*");
            c.subscribe(props.getProductTopic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    ConsumeConcurrentlyStatus status = handleSingleMessage(msg);
                    if (status == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            c.start();
            this.consumer = c;
            log.info("RocketMQ consumer started: group={} topics=[{}, {}]",
                props.getConsumerGroup(), props.getOrderTopic(), props.getProductTopic());
        } catch (Exception ex) {
            log.warn("RocketMQ consumer startup failed (fail-open): {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        DefaultMQPushConsumer c = this.consumer;
        if (c != null) {
            c.shutdown();
            this.consumer = null;
        }
    }

    ConsumeConcurrentlyStatus handleSingleMessage(MessageExt msg) {
        String topic = msg.getTopic();
        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
        String eventId = extractEventId(msg, body);
        String dedupKey = DEDUP_PREFIX + eventId;

        if (!acquireDedup(dedupKey)) {
            log.info("RocketMQ duplicate event skipped: topic={} eventId={}", topic, eventId);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        String path;
        if (props.getOrderTopic().equals(topic)) {
            path = "/api/internal/events/order-status";
        } else if (props.getProductTopic().equals(topic)) {
            path = "/api/internal/events/product-sync";
        } else {
            log.warn("Ignoring unknown RocketMQ topic: {}", topic);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        String url = props.getAgentRuntimeBaseUrl().replaceAll("/+$", "") + path;
        try {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
            log.info("Dispatched RocketMQ event to agent-runtime: topic={} eventId={} url={}",
                topic, eventId, url);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        } catch (Exception ex) {
            releaseDedup(dedupKey);
            log.warn("Dispatch to agent-runtime failed, scheduling RocketMQ retry: eventId={} err={}",
                eventId, ex.getMessage());
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    private String extractEventId(MessageExt msg, String body) {
        if (msg.getKeys() != null && !msg.getKeys().isBlank()) {
            return msg.getKeys().trim();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.hasNonNull("eventId")) {
                return node.get("eventId").asText();
            }
        } catch (Exception ignored) {
        }
        return msg.getMsgId() != null ? msg.getMsgId() : String.valueOf(body.hashCode());
    }

    private boolean acquireDedup(String key) {
        try {
            Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(props.getConsumeDedupTtlSeconds()));
            return !Boolean.FALSE.equals(ok);
        } catch (Exception ex) {
            log.warn("Redis dedup unavailable, proceeding fail-open: {}", ex.getMessage());
            return true;
        }
    }

    private void releaseDedup(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }
}
