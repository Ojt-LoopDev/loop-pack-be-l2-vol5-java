package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Point {

    @Column(name = "point_balance", nullable = false)
    private long balance;

    protected Point() {}

    public void charge(long amount) {
        if (amount <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 양수여야 합니다.");
        }
        this.balance += amount;
    }

    public long getBalance() {
        return balance;
    }
}
