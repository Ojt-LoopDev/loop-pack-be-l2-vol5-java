package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "brand")
public class BrandModel extends BaseEntity {

    private String name;
    private String description;
    private String category;

    protected BrandModel() {}

    public BrandModel(String name, String description, String category) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "이름은 비어있을 수 없습니다.");
        }
        if (description == null || description.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "설명은 비어있을 수 없습니다.");
        }
        if (category == null || category.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "카테고리는 비어있을 수 없습니다.");
        }

        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }
}
