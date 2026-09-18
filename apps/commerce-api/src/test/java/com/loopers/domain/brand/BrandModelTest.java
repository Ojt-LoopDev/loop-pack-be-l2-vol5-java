package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandModelTest {
    @DisplayName("브랜드 모델을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("이름, 설명, 카테고리가 모두 주어지면, 정상적으로 생성된다.")
        @Test
        void createsBrandModel_whenAllFieldsAreProvided() {
            // arrange
            String name = "나이키";
            String description = "스포츠 브랜드";
            String category = "신발/의류";

            // act
            BrandModel brand = new BrandModel(name, description, category);

            // assert
            assertAll(
                () -> assertThat(brand.getId()).isNotNull(),
                () -> assertThat(brand.getName()).isEqualTo(name),
                () -> assertThat(brand.getDescription()).isEqualTo(description),
                () -> assertThat(brand.getCategory()).isEqualTo(category)
            );
        }

        @DisplayName("이름이 빈 값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("   ", "설명", "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("설명이 빈 값이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenDescriptionIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", "", "카테고리")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("카테고리가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenCategoryIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("이름", "설명", null)
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
