package com.loopers.domain.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<ProductModel> find(Long id);

    List<ProductModel> findAllActive();

    boolean existsActiveByBrandId(Long brandId);

    Page<ProductModel> findActive(Long brandId, ProductSort sort, Pageable pageable);

    ProductModel save(ProductModel product);
}
