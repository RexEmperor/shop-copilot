package com.shopcopilot.business.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopcopilot.business.aftersale.entity.AftersaleTicket;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AftersaleTicketMapper extends BaseMapper<AftersaleTicket> {

    @Select("""
        SELECT COUNT(*)
        FROM aftersale_ticket
        WHERE order_no = #{orderNo} AND status IN (0, 1)
        """)
    long countActiveByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
        SELECT id
        FROM aftersale_ticket
        WHERE order_no = #{orderNo} AND status IN (0, 1, 2)
        LIMIT 1
        FOR UPDATE
        """)
    Long findBlockingIdByOrderNoForUpdate(@Param("orderNo") String orderNo);

    @Update("""
        UPDATE aftersale_ticket
        SET status = 1, reviewed_at = #{reviewedAt}
        WHERE id = #{id} AND status = 0
        """)
    int approve(@Param("id") Long id, @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("""
        UPDATE aftersale_ticket
        SET status = 3, review_remark = #{reviewRemark}, reviewed_at = #{reviewedAt}
        WHERE id = #{id} AND status = 0
        """)
    int reject(@Param("id") Long id,
               @Param("reviewRemark") String reviewRemark,
               @Param("reviewedAt") LocalDateTime reviewedAt);

    @Update("""
        UPDATE aftersale_ticket
        SET status = 2, refunded_at = #{refundedAt}
        WHERE id = #{id} AND status = 1
        """)
    int completeRefund(@Param("id") Long id, @Param("refundedAt") LocalDateTime refundedAt);
}
