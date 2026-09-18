package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class Stock {

    private int remaining;

    public Stock(int remaining) {
        if (remaining < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "초기 재고는 음수일 수 없습니다.");
        }
        this.remaining = remaining;
    }

    public void decrease(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감할 수량은 0보다 커야 합니다.");
        }
        if (quantity > remaining) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고보다 많은 수량을 차감할 수 없습니다.");
        }
        remaining -= quantity;
    }

    public int remaining() {
        return remaining;
    }
}
