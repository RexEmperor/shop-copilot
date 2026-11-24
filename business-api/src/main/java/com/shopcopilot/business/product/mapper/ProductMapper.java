package com.shopcopilot.business.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shopcopilot.business.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    @Update("""
        UPDATE product
        SET stock = stock - #{quantity}, version = version + 1
        WHERE id = #{productId} AND status = 1 AND stock >= #{quantity}
        """)
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    @Update("""
        UPDATE product
        SET stock = stock + #{quantity}, version = version + 1
        WHERE id = #{productId} AND stock <= 2147483647 - #{quantity}
        """)
    int restoreStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
