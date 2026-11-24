package com.shopcopilot.business.product.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ProductInventoryIntegrationTest {

    @Autowired
    private ProductMapper productMapper;

    @Test
    @DisplayName("条件 UPDATE 原子扣库存且库存不足时不会继续扣减")
    void should_deductStockAtomically_when_stockEnough() {
        Product product = insertProduct(1, ProductStatus.ON_SALE);

        assertEquals(1, productMapper.deductStock(product.getId(), 1));
        assertEquals(0, productMapper.deductStock(product.getId(), 1));

        Product stored = productMapper.selectById(product.getId());
        assertEquals(0, stored.getStock());
        assertEquals(1, stored.getVersion());
    }

    @Test
    @DisplayName("下架商品不允许扣减库存")
    void should_rejectStockDeduction_when_productOffSale() {
        Product product = insertProduct(5, ProductStatus.ON_SALE);
        product.setStatus(ProductStatus.OFF_SALE);
        productMapper.updateById(product);

        assertEquals(0, productMapper.deductStock(product.getId(), 1));
        assertEquals(5, productMapper.selectById(product.getId()).getStock());
    }

    @Test
    @DisplayName("取消订单时下架商品也可以恢复库存")
    void should_restoreStock_when_productOffSale() {
        Product product = insertProduct(5, ProductStatus.OFF_SALE);

        assertEquals(1, productMapper.restoreStock(product.getId(), 2));

        Product stored = productMapper.selectById(product.getId());
        assertEquals(7, stored.getStock());
        assertEquals(1, stored.getVersion());
    }

    @Test
    @DisplayName("库存恢复导致整数溢出时拒绝更新")
    void should_rejectStockRestore_when_stockWouldOverflow() {
        Product product = insertProduct(Integer.MAX_VALUE, ProductStatus.ON_SALE);

        assertEquals(0, productMapper.restoreStock(product.getId(), 1));

        Product stored = productMapper.selectById(product.getId());
        assertEquals(Integer.MAX_VALUE, stored.getStock());
        assertEquals(0, stored.getVersion());
    }

    private Product insertProduct(int stock, ProductStatus status) {
        Product product = new Product();
        product.setName("库存测试商品");
        product.setCategory("测试");
        product.setDescription("用于验证原子扣库存");
        product.setPriceCents(100L);
        product.setStock(stock);
        product.setStatus(status);
        product.setVersion(0);
        productMapper.insert(product);
        return product;
    }
}
