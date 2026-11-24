package com.shopcopilot.business.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shopcopilot.business.common.BusinessException;
import com.shopcopilot.business.common.PageResult;
import com.shopcopilot.business.product.dto.ProductSaveRequest;
import com.shopcopilot.business.product.entity.Product;
import com.shopcopilot.business.product.enums.ProductStatus;
import com.shopcopilot.business.product.mapper.ProductMapper;
import com.shopcopilot.business.product.query.ProductQuery;
import com.shopcopilot.business.product.service.ProductService;
import com.shopcopilot.business.product.vo.ProductVO;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;

    public ProductServiceImpl(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    public PageResult<ProductVO> page(ProductQuery query) {
        int statusCode = query.getStatus() == null
            ? ProductStatus.ON_SALE.getCode()
            : query.getStatus();
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;
        String category = StringUtils.hasText(query.getCategory()) ? query.getCategory().trim() : null;
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
            .like(keyword != null, Product::getName, keyword)
            .eq(category != null, Product::getCategory, category)
            .eq(Product::getStatus, ProductStatus.fromCode(statusCode))
            .orderByDesc(Product::getCreatedAt)
            .orderByDesc(Product::getId);

        var page = productMapper.selectPage(query.toPage(), wrapper);
        List<ProductVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public ProductVO getById(Long id) {
        return toVO(requireProduct(id));
    }

    @Override
    @Transactional
    public Long create(ProductSaveRequest request) {
        Product product = new Product();
        applyRequest(product, request);
        product.setStatus(ProductStatus.ON_SALE);
        product.setVersion(0);
        productMapper.insert(product);
        return product.getId();
    }

    @Override
    @Transactional
    public void update(Long id, ProductSaveRequest request) {
        Product product = requireProduct(id);
        applyRequest(product, request);
        updateProduct(product);
    }

    @Override
    @Transactional
    public void changeStatus(Long id, Integer status) {
        Product product = requireProduct(id);
        ProductStatus targetStatus = ProductStatus.fromCode(status);
        if (product.getStatus() == targetStatus) {
            return;
        }
        product.setStatus(targetStatus);
        updateProduct(product);
    }

    private Product requireProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        return product;
    }

    private void applyRequest(Product product, ProductSaveRequest request) {
        product.setName(request.name().trim());
        product.setCategory(request.category().trim());
        product.setDescription(request.description().trim());
        product.setPriceCents(request.priceCents());
        product.setStock(request.stock());
    }

    private void updateProduct(Product product) {
        int updatedRows = productMapper.updateById(product);
        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "商品已被他人修改，请刷新后重试");
        }
    }

    private ProductVO toVO(Product product) {
        ProductStatus status = product.getStatus();
        return new ProductVO(
            product.getId(),
            product.getName(),
            product.getCategory(),
            product.getDescription(),
            product.getPriceCents(),
            product.getStock(),
            status.getCode(),
            status.getText(),
            product.getVersion(),
            product.getCreatedAt(),
            product.getUpdatedAt()
        );
    }
}
