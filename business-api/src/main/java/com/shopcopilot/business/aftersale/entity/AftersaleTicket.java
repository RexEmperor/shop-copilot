package com.shopcopilot.business.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.aftersale.enums.AftersaleType;
import java.time.LocalDateTime;

@TableName("aftersale_ticket")
public class AftersaleTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketNo;

    private String orderNo;

    private Long userId;

    private AftersaleType type;

    private AftersaleStatus status;

    private Long refundCents;

    private String reason;

    private String reviewRemark;

    private LocalDateTime reviewedAt;

    private LocalDateTime refundedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketNo() {
        return ticketNo;
    }

    public void setTicketNo(String ticketNo) {
        this.ticketNo = ticketNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public AftersaleType getType() {
        return type;
    }

    public void setType(AftersaleType type) {
        this.type = type;
    }

    public AftersaleStatus getStatus() {
        return status;
    }

    public void setStatus(AftersaleStatus status) {
        this.status = status;
    }

    public Long getRefundCents() {
        return refundCents;
    }

    public void setRefundCents(Long refundCents) {
        this.refundCents = refundCents;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReviewRemark() {
        return reviewRemark;
    }

    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getRefundedAt() {
        return refundedAt;
    }

    public void setRefundedAt(LocalDateTime refundedAt) {
        this.refundedAt = refundedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
