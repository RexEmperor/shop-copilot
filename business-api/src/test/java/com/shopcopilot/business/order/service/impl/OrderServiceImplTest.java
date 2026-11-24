package com.shopcopilot.business.order.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.order.dto.OrderCreateRequest;
import com.shopcopilot.business.order.entity.Order;
import com.shopcopilot.business.order.mapper.OrderMapper;
import com.shopcopilot.business.order.state.OrderStateMachine;
import com.shopcopilot.business.order.vo.OrderVO;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private ProductMapper productMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderStateMachine orderStateMachine;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    @DisplayName("创建订单时使用后端价格计算金额并原子扣减库存")
    void should_createOrderWithBackendAmount_when_productOnSaleAndStockEnough() {
        Product product = product(1L, ProductStatus.ON_SALE, 19900L, 120, 3);
        OrderCreateRequest request = request(1L, 2);
        when(productMapper.selectById(1L)).thenReturn(product);
        when(productMapper.deductStock(1L, 2)).thenReturn(1);
        when(orderMapper.insert(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            order.setId(100L);
            return 1;
        });

        OrderVO result = orderService.create(request);

        assertNotNull(result.orderNo());
        assertEquals(100L, result.id());
        assertEquals(10001L, result.userId());
        assertEquals(1L, result.productId());
        assertEquals("无线蓝牙耳机 Pro", result.productNameSnapshot());
        assertEquals(19900L, result.unitPriceCents());
        assertEquals(2, result.quantity());
        assertEquals(39800L, result.totalAmountCents());
        assertEquals(0, result.status());
        assertEquals("待支付", result.statusText());
        assertEquals(0, result.version());
        assertEquals("郭克", result.receiverName());
        assertEquals("13800138000", result.receiverPhone());
        assertEquals("北京市朝阳区测试路 1 号", result.shippingAddress());
        verify(productMapper).deductStock(1L, 2);
        verify(orderMapper).insert(any(Order.class));
    }

    @Test
    @DisplayName("商品不存在时返回 404 且不扣库存不落单")
    void should_throwNotFound_when_productMissing() {
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.create(request(999L, 1)));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("商品不存在", exception.getMessage());
        verify(productMapper, never()).deductStock(any(), any());
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("商品下架时返回 409 且不扣库存不落单")
    void should_throwConflict_when_productOffSale() {
        when(productMapper.selectById(10L)).thenReturn(
            product(10L, ProductStatus.OFF_SALE, 9900L, 10, 0));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.create(request(10L, 1)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("商品已下架", exception.getMessage());
        verify(productMapper, never()).deductStock(any(), any());
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("库存不足时返回 409 且不创建订单")
    void should_throwConflict_when_stockInsufficient() {
        when(productMapper.selectById(1L)).thenReturn(
            product(1L, ProductStatus.ON_SALE, 19900L, 1, 5));
        when(productMapper.deductStock(1L, 2)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.create(request(1L, 2)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("商品库存不足或已下架", exception.getMessage());
        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("后端金额计算溢出时返回 400 且不扣库存不落单")
    void should_throwBadRequest_when_totalAmountOverflow() {
        when(productMapper.selectById(1L)).thenReturn(
            product(1L, ProductStatus.ON_SALE, Long.MAX_VALUE, 10, 0));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> orderService.create(request(1L, 2)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("订单金额超出范围", exception.getMessage());
        verify(productMapper, never()).deductStock(any(), any());
        verifyNoInteractions(orderMapper);
    }

    private Product product(Long id, ProductStatus status, long priceCents, int stock, int version) {
        Product product = new Product();
        product.setId(id);
        product.setName("无线蓝牙耳机 Pro");
        product.setCategory("数码");
        product.setDescription("测试商品");
        product.setPriceCents(priceCents);
        product.setStock(stock);
        product.setStatus(status);
        product.setVersion(version);
        return product;
    }

    private OrderCreateRequest request(Long productId, int quantity) {
        return new OrderCreateRequest(
            productId,
            quantity,
            " 郭克 ",
            "13800138000",
            "北京市朝阳区测试路 1 号"
        );
    }
}
