package com.loopers.domain.user;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PointTest {

    @DisplayName("포인트를 충전할 때,")
    @Nested
    class Charge {
        @DisplayName("양수 금액이면, 잔액에 더해진다.")
        @Test
        void increasesBalance_whenAmountIsPositive() {
            // arrange
            Point point = new Point();

            // act
            point.charge(1000L);

            // assert
            assertThat(point.getBalance()).isEqualTo(1000L);
        }

        @DisplayName("0이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsZero() {
            // arrange
            Point point = new Point();

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(0L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("음수이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // arrange
            Point point = new Point();

            // act
            CoreException result = assertThrows(CoreException.class, () -> point.charge(-1000L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
