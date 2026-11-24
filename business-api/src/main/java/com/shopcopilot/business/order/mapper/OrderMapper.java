package com.shopcopilot.business.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopcopilot.business.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    @Select("SELECT * FROM orders WHERE order_no = #{orderNo} FOR UPDATE")
    Order selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
