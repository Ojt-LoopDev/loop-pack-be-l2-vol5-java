package com.loopers.domain.payment;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentAttemptTest {

    private static final String PROVIDER_ORDER_ID = "LP-ORD-0000000042";
    private static final String TX_KEY = "TEST-TX-0042";
    private static final long AMOUNT = 7000L;

    private static PaymentAttempt requested() {
        return new PaymentAttempt(42L, "key-A", PROVIDER_ORDER_ID, AMOUNT);
    }

    private static PaymentObservation observed(PaymentObservation.Status status) {
        return new PaymentObservation(PROVIDER_ORDER_ID, TX_KEY, status, AMOUNT);
    }

    @DisplayName("결제 시도를 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("요청됨 상태로 만들어지고, 발송 시작 시각이 기록된다. (INV-005)")
        @Test
        void createsRequestedAttempt_withSendStartedAt() {
            // act
            PaymentAttempt attempt = requested();

            // assert
            assertAll(
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED),
                () -> assertThat(attempt.getOrderId()).isEqualTo(42L),
                () -> assertThat(attempt.getTransactionKey()).isNull(),
                () -> assertThat(attempt.getSendStartedAt()).isNotNull()
            );
        }

        @DisplayName("결제 시도 키가 비어 있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAttemptKeyIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class,
                () -> new PaymentAttempt(42L, " ", PROVIDER_ORDER_ID, AMOUNT));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("금액이 음수면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenAmountIsNegative() {
            // act
            CoreException result = assertThrows(CoreException.class,
                () -> new PaymentAttempt(42L, "key-A", PROVIDER_ORDER_ID, -1L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("create 응답을 반영할 때, ")
    @Nested
    class CreateResponse {
        @DisplayName("202 PENDING이면, 처리중으로 바뀌고 transactionKey가 기록된다. 효과는 없다.")
        @Test
        void becomesPending_whenAccepted() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            ApplyResult result = attempt.accept(TX_KEY);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("응답을 받지 못하면, 실패가 아니라 UNKNOWN이 된다. (INV-004)")
        @Test
        void becomesUnknown_whenResponseIsLost() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            attempt.markUnknown();

            // assert
            assertAll(
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN),
                () -> assertThat(attempt.getTransactionKey()).isNull()
            );
        }

        @DisplayName("효과 없는 확정 거절(422)이면, FAILED로 전이하고 효과가 있다. transactionKey는 꾸며 넣지 않는다.")
        @Test
        void fails_whenRejectedBeforeCreate() {
            // arrange
            PaymentAttempt attempt = requested();

            // act
            ApplyResult result = attempt.rejectBeforeCreate();

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attempt.getTransactionKey()).isNull()
            );
        }

        @DisplayName("callback이 먼저 SUCCESS로 종결한 뒤 늦게 온 생성 응답·timeout 처리는, 상태를 되돌리지 않는다. (INV-003 반례 1)")
        @Test
        void keepsTerminalStatus_whenLateCreateResponseArrives() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // act
            ApplyResult accepted = attempt.accept(TX_KEY);
            attempt.markUnknown();

            // assert
            assertAll(
                () -> assertThat(accepted).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }
    }

    @DisplayName("PG 조회 결과를 반영할 때, ")
    @Nested
    class Apply {
        @DisplayName("미종결 시도에 SUCCESS가 확인되면, SUCCESS로 전이하고 효과가 있다.")
        @Test
        void succeeds_whenSuccessIsObservedOnPendingAttempt() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.SUCCEEDED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }

        @DisplayName("응답을 잃은(UNKNOWN) 시도에 목록 조회로 거래가 확인되면, transactionKey를 알게 되고 결과로 전이한다.")
        @Test
        void learnsTransactionKeyAndFails_whenFailedIsObservedOnUnknownAttempt() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.FAILED));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("미종결 시도에 PENDING이 확인되면, 처리중으로 남고 효과는 없다.")
        @Test
        void staysPending_whenPendingIsObserved() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.PENDING));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }

        @DisplayName("같은 FAILED가 두 번 들어오면, 두 번째는 효과가 없다 — 재고가 두 번 반환되지 않는다. (INV-002)")
        @Test
        void hasNoEffect_whenSameFailedArrivesTwice() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);

            // act
            ApplyResult first = attempt.apply(observed(PaymentObservation.Status.FAILED));
            ApplyResult second = attempt.apply(observed(PaymentObservation.Status.FAILED));

            // assert
            assertAll(
                () -> assertThat(first).isEqualTo(ApplyResult.FAILED),
                () -> assertThat(second).isEqualTo(ApplyResult.NO_EFFECT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED)
            );
        }

        @DisplayName("FAILED로 종결된 시도에 SUCCESS가 확인되면, 상태를 바꾸지 않고 충돌로 알린다. (INV-003 반례 2)")
        @Test
        void reportsConflict_whenDifferentOutcomeArrivesAfterTerminal() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.rejectBeforeCreate();

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED)
            );
        }

        @DisplayName("SUCCESS로 종결된 시도에 PENDING이 확인되면, 되돌리지 않고 충돌로 알린다.")
        @Test
        void reportsConflict_whenPendingArrivesAfterSuccess() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.apply(observed(PaymentObservation.Status.SUCCESS));

            // act
            ApplyResult result = attempt.apply(observed(PaymentObservation.Status.PENDING));

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS)
            );
        }

        @DisplayName("관찰한 금액이 저장된 금액과 다르면, 미종결이어도 전이하지 않고 충돌로 알린다.")
        @Test
        void reportsConflict_whenAmountDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);
            PaymentObservation otherAmount = new PaymentObservation(
                PROVIDER_ORDER_ID, TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT + 1);

            // act
            ApplyResult result = attempt.apply(otherAmount);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING)
            );
        }

        @DisplayName("관찰한 주문 식별자가 다르면, 충돌로 알린다.")
        @Test
        void reportsConflict_whenProviderOrderIdDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.markUnknown();
            PaymentObservation otherOrder = new PaymentObservation(
                "LP-ORD-0000000043", TX_KEY, PaymentObservation.Status.SUCCESS, AMOUNT);

            // act
            ApplyResult result = attempt.apply(otherOrder);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN)
            );
        }

        @DisplayName("이미 아는 transactionKey와 다른 거래가 관찰되면, 충돌로 알린다.")
        @Test
        void reportsConflict_whenTransactionKeyDiffers() {
            // arrange
            PaymentAttempt attempt = requested();
            attempt.accept(TX_KEY);
            PaymentObservation otherTransaction = new PaymentObservation(
                PROVIDER_ORDER_ID, "TEST-TX-9999", PaymentObservation.Status.SUCCESS, AMOUNT);

            // act
            ApplyResult result = attempt.apply(otherTransaction);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(ApplyResult.CONFLICT),
                () -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(attempt.getTransactionKey()).isEqualTo(TX_KEY)
            );
        }
    }
}
