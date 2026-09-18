package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public ProductModel getProduct(Long id) {
        return productRepository.find(id)
            .filter(product -> product.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 상품을 찾을 수 없습니다."));
    }

    @Transactional(readOnly = true)
    public List<ProductModel> getProducts() {
        return productRepository.findAllActive();
    }

    @Transactional(readOnly = true)
    public Page<ProductModel> getProducts(Long brandId, ProductSort sort, Pageable pageable) {
        return productRepository.findActive(brandId, sort, pageable);
    }

    /**
     * 주어진 브랜드를 참조하는 삭제되지 않은 상품이 하나라도 있는지 확인한다.
     * 재고 수량과 무관하다 — 재고 0인 상품도 "삭제되지 않았다"는 사실만으로 포함된다.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveProduct(Long brandId) {
        return productRepository.existsActiveByBrandId(brandId);
    }

    @Transactional
    public ProductModel createProduct(String name, Long price, Long brandId, int initialStock) {
        ProductModel product = new ProductModel(name, price, brandId, initialStock);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel updateProduct(Long id, String name, Long price) {
        ProductModel product = getProduct(id);
        product.update(name, price);
        return productRepository.save(product);
    }

    @Transactional
    public ProductModel changeStock(Long id, int quantity) {
        ProductModel product = getProduct(id);
        product.changeStock(quantity);
        return productRepository.save(product);
    }

    @Transactional
    public void deleteProduct(Long id) {
        ProductModel product = getProduct(id);
        product.delete();
        productRepository.save(product);
    }
}
