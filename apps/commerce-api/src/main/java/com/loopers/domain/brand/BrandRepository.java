package com.loopers.domain.brand;

import java.util.Optional;

public interface BrandRepository {
    Optional<BrandModel> find(Long id);
}
