package com.shopcopilot.business.aftersale.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopcopilot.business.aftersale.dto.AftersaleCreateRequest;
import com.shopcopilot.business.aftersale.entity.AftersaleTicket;
import com.shopcopilot.business.aftersale.enums.AftersaleStatus;
import com.shopcopilot.business.aftersale.enums.AftersaleType;
import com.shopcopilot.business.aftersale.mapper.AftersaleTicketMapper;
import com.shopcopilot.business.aftersale.query.AftersaleQuery;
import com.shopcopilot.business.aftersale.service.AftersaleService;
import com.shopcopilot.business.aftersale.state.AftersaleStateMachine;
import com.shopcopilot.business.aftersale.vo.AftersaleVO;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.enums.OrderStatus;
import com.shopcopilot.business.order.mapper.OrderMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AftersaleServiceImpl implements AftersaleService {

    private final OrderMapper orderMapper;
    private final AftersaleTicketMapper aftersaleMapper;
    private final AftersaleStateMachine stateMachine;

    public AftersaleServiceImpl(
            OrderMapper orderMapper,
            AftersaleTicketMapper aftersaleMapper,
            AftersaleStateMachine stateMachine) {
        this.orderMapper = orderMapper;
        this.aftersaleMapper = aftersaleMapper;
        this.stateMachine = stateMachine;
    }

    @Override
    @Transactional
    public Long create(AftersaleCreateRequest request) {
        String orderNo = request.orderNo().trim();
        Order order = orderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        validateOrderAndType(order, request.type());
        if (aftersaleMapper.countActiveByOrderNo(orderNo) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "该订单已有进行中的售后工单");
        }

        AftersaleTicket ticket = new AftersaleTicket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setOrderNo(orderNo);
        ticket.setUserId(order.getUserId());
        ticket.setType(AftersaleType.fromCode(request.type()));
        ticket.setStatus(AftersaleStatus.PENDING);
        ticket.setRefundCents(order.getTotalAmountCents());
        ticket.setReason(request.reason().trim());
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);

        int insertedRows = aftersaleMapper.insert(ticket);
        if (insertedRows == 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "售后工单创建失败");
        }
        return ticket.getId();
    }

    @Override
    @Transactional
    public AftersaleVO approve(Long id) {
        AftersaleTicket ticket = requireTicket(id);
        AftersaleStatus nextStatus = stateMachine.transition(ticket.getStatus(), AftersaleStatus.REFUNDING);
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = aftersaleMapper.approve(id, now);
        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "售后状态不允许流转");
        }

        ticket.setStatus(nextStatus);
        ticket.setReviewedAt(now);
        ticket.setUpdatedAt(now);
        return toVO(ticket);
    }

    @Override
    @Transactional
    public AftersaleVO reject(Long id, String reviewRemark) {
        AftersaleTicket ticket = requireTicket(id);
        AftersaleStatus nextStatus = stateMachine.transition(ticket.getStatus(), AftersaleStatus.REJECTED);
        String remark = reviewRemark.trim();
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = aftersaleMapper.reject(id, remark, now);
        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "售后状态不允许流转");
        }

        ticket.setStatus(nextStatus);
        ticket.setReviewRemark(remark);
        ticket.setReviewedAt(now);
        ticket.setUpdatedAt(now);
        return toVO(ticket);
    }

    @Override
    @Transactional
    public AftersaleVO completeRefund(Long id) {
        AftersaleTicket ticket = requireTicket(id);
        AftersaleStatus nextStatus = stateMachine.transition(ticket.getStatus(), AftersaleStatus.REFUNDED);
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = aftersaleMapper.completeRefund(id, now);
        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "售后状态不允许流转");
        }

        ticket.setStatus(nextStatus);
        ticket.setRefundedAt(now);
        ticket.setUpdatedAt(now);
        return toVO(ticket);
    }

    @Override
    public AftersaleVO getById(Long id) {
        return toVO(requireTicket(id));
    }

    @Override
    public PageResult<AftersaleVO> page(AftersaleQuery query) {
        String orderNo = StringUtils.hasText(query.getOrderNo()) ? query.getOrderNo().trim() : null;
        LambdaQueryWrapper<AftersaleTicket> wrapper = new LambdaQueryWrapper<AftersaleTicket>()
            .eq(orderNo != null, AftersaleTicket::getOrderNo, orderNo)
            .eq(query.getStatus() != null, AftersaleTicket::getStatus,
                AftersaleStatus.fromCode(query.getStatus()))
            .orderByDesc(AftersaleTicket::getCreatedAt)
            .orderByDesc(AftersaleTicket::getId);

        var page = aftersaleMapper.selectPage(query.toPage(), wrapper);
        List<AftersaleVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private void validateOrderAndType(Order order, int type) {
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PAID && status != OrderStatus.SHIPPED && status != OrderStatus.DELIVERED) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前订单状态不允许发起售后");
        }
        if (AftersaleType.fromCode(type) == AftersaleType.RETURN_REFUND
                && status != OrderStatus.SHIPPED && status != OrderStatus.DELIVERED) {
            throw new BusinessException(HttpStatus.CONFLICT, "当前订单状态不允许退货退款");
        }
    }

    private AftersaleTicket requireTicket(Long id) {
        AftersaleTicket ticket = aftersaleMapper.selectById(id);
        if (ticket == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "售后工单不存在");
        }
        return ticket;
    }

    private String generateTicketNo() {
        return "AS" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }

    private AftersaleVO toVO(AftersaleTicket ticket) {
        return new AftersaleVO(
            ticket.getId(),
            ticket.getTicketNo(),
            ticket.getOrderNo(),
            ticket.getUserId(),
            ticket.getType().getCode(),
            ticket.getStatus().getCode(),
            ticket.getStatus().getText(),
            ticket.getRefundCents(),
            ticket.getReason(),
            ticket.getReviewRemark(),
            ticket.getReviewedAt(),
            ticket.getRefundedAt(),
            ticket.getCreatedAt(),
            ticket.getUpdatedAt()
        );
    }
}
