package com.loopers.domain.brand;

import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class BrandServiceIntegrationTest {
    @Autowired
    private BrandService brandService;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 조회할 때,")
    @Nested
    class Get {
        @DisplayName("존재하는 브랜드 ID를 주면, 해당 브랜드 정보를 반환한다.")
        @Test
        void returnsBrand_whenValidIdIsProvided() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act
            BrandModel result = brandService.getBrand(brand.getId());

            // assert
            assertAll(
                () -> assertThat(result).isNotNull(),
                () -> assertThat(result.getId()).isEqualTo(brand.getId()),
                () -> assertThat(result.getName()).isEqualTo(brand.getName())
            );
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() {
            // arrange
            Long invalidId = 999L;

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.getBrand(invalidId)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드 ID를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.getBrand(brand.getId())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
