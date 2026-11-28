package com.shopcopilot.business.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.mq")
public class MqProperties {

    private boolean enabled = false;
    private String namesrvAddr = "127.0.0.1:9876";
    private String producerGroup = "shop-copilot-business-producer";
    private String consumerGroup = "shop-copilot-agent-dispatcher";
    private String orderTopic = "shop-copilot-order-events";
    private String productTopic = "shop-copilot-product-events";
    private String agentRuntimeBaseUrl = "http://127.0.0.1:8000";
    private long consumeDedupTtlSeconds = 86400L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNamesrvAddr() {
        return namesrvAddr;
    }

    public void setNamesrvAddr(String namesrvAddr) {
        this.namesrvAddr = namesrvAddr;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getOrderTopic() {
        return orderTopic;
    }

    public void setOrderTopic(String orderTopic) {
        this.orderTopic = orderTopic;
    }

    public String getProductTopic() {
        return productTopic;
    }

    public void setProductTopic(String productTopic) {
        this.productTopic = productTopic;
    }

    public String getAgentRuntimeBaseUrl() {
        return agentRuntimeBaseUrl;
    }

    public void setAgentRuntimeBaseUrl(String agentRuntimeBaseUrl) {
        this.agentRuntimeBaseUrl = agentRuntimeBaseUrl;
    }

    public long getConsumeDedupTtlSeconds() {
        return consumeDedupTtlSeconds;
    }

    public void setConsumeDedupTtlSeconds(long consumeDedupTtlSeconds) {
        this.consumeDedupTtlSeconds = consumeDedupTtlSeconds;
    }
}
