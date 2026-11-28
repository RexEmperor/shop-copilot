package com.shopcopilot.business.product.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.mq.DomainEventPublisher;
import com.shopcopilot.business.product.cache.ProductCache;
import com.shopcopilot.business.product.dto.ProductSaveRequest;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductCache productCache;

    @Mock
    private DomainEventPublisher eventPublisher;

    @InjectMocks
    private ProductServiceImpl productService;

    @Test
    void should_createOnSaleProduct_withVersionZero() {
        ProductSaveRequest request = new ProductSaveRequest(
            "测试商品", "测试", "测试描述", 12345L, 10);
        when(productMapper.insert(any(Product.class))).thenAnswer(invocation -> {
            Product product = invocation.getArgument(0);
            product.setId(100L);
            return 1;
        });

        Long id = productService.create(request);

        assertEquals(100L, id);
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        Product inserted = captor.getValue();
        assertEquals("测试商品", inserted.getName());
        assertEquals(12345L, inserted.getPriceCents());
        assertEquals(ProductStatus.ON_SALE, inserted.getStatus());
        assertEquals(0, inserted.getVersion());
    }

    @Test
    void should_throwNotFound_whenProductMissing() {
        when(productMapper.selectById(1L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> productService.getById(1L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("商品不存在", exception.getMessage());
    }

    @Test
    void should_throwConflict_whenOptimisticUpdateFails() {
        Product product = new Product();
        product.setId(1L);
        product.setStatus(ProductStatus.ON_SALE);
        product.setVersion(0);
        ProductSaveRequest request = new ProductSaveRequest(
            "更新商品", "测试", "更新描述", 19900L, 5);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(productMapper.updateById(product)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> productService.update(1L, request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("商品已被他人修改，请刷新后重试", exception.getMessage());
    }

    @Test
    void should_skipUpdate_whenStatusUnchanged() {
        Product product = new Product();
        product.setId(1L);
        product.setStatus(ProductStatus.ON_SALE);
        product.setVersion(0);
        when(productMapper.selectById(1L)).thenReturn(product);

        productService.changeStatus(1L, ProductStatus.ON_SALE.getCode());

        verify(productMapper, never()).updateById(any(Product.class));
    }
}
