package com.loopers.domain.payment;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.ZonedDateTime;

/**
 * 주문 하나의 PG 결제 시도. 상태 전이 규칙을 스스로 지킨다 — 결과가 들어오는 경로(callback 워커·reconcile·
 * 스케줄러·관리자)가 늘어나도 규칙이 빠지지 않게 하기 위해서다(docs/week5/design.md D-001).
 *
 * - 생성 = 발송 시작 기록. 이 행이 commit된 뒤에만 create를 보낸다(INV-005).
 * - orderId는 생성자에서만 정해진다. callback 경로가 잠그지 않고 읽어도 안전한 근거다(INV-007).
 * - 미종결 → 종결 전이는 한 번뿐이고, 호출자는 전이가 일어났을 때만 주문·재고 효과를 낸다(INV-002).
 * - 종결 뒤 같은 결과는 무시, 다른 결과는 상태를 바꾸지 않고 CONFLICT로 알린다(INV-003).
 *
 * 동시에 들어오는 반영은 호출자가 행 락으로 직렬화한다(D-002). 이 클래스는 잠근 뒤 읽은 상태로 판단한다.
 */
// UK(order_id): 주문당 한 시도. 주문 락이 이미 줄을 세우지만, 주문 락을 거치지 않는 경로(관리자·배치)가 생겨도 DB가 지키는 마지막 방어선이다.
// UK(attempt_key): 같은 키가 서로 다른 주문에 동시에 들어오면 각자 다른 주문을 잠가 직렬화되지 않는다 — 이 제약이 실제 방어선이다.
// (docs/week5/design.md D-016) provider_order_id·transaction_key 인덱스는 callback 워커가 본문의 키로 시도를 찾는 경로다(D-021).
@Entity
@Table(
    name = "payment_attempt",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_attempt_order_id", columnNames = "order_id"),
        @UniqueConstraint(name = "uk_payment_attempt_attempt_key", columnNames = "attempt_key")
    },
    indexes = {
        @Index(name = "idx_payment_attempt_provider_order_id", columnList = "provider_order_id"),
        @Index(name = "idx_payment_attempt_transaction_key", columnList = "transaction_key")
    }
)
public class PaymentAttempt extends BaseEntity {

    private Long orderId;
    private String attemptKey;
    private String providerOrderId;
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    private PaymentAttemptStatus status;

    private Long amount;
    private ZonedDateTime sendStartedAt;

    protected PaymentAttempt() {}

    public PaymentAttempt(Long orderId, String attemptKey, String providerOrderId, long amount) {
        if (orderId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도의 주문 ID는 비어있을 수 없습니다.");
        }
        if (attemptKey == null || attemptKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 시도 키는 비어있을 수 없습니다.");
        }
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "providerOrderId는 비어있을 수 없습니다.");
        }
        if (amount < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 음수일 수 없습니다.");
        }
        this.orderId = orderId;
        this.attemptKey = attemptKey;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = PaymentAttemptStatus.REQUESTED;
        this.sendStartedAt = ZonedDateTime.now();
    }

    /**
     * create가 202 PENDING으로 수락됐다. 이미 종결됐다면(빠른 callback이 먼저 반영한 경우) 늦게 도착한
     * 생성 응답이 상태를 되돌리지 않는다(INV-003 반례 1).
     */
    public ApplyResult accept(String transactionKey) {
        if (transactionKey == null || transactionKey.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "transactionKey는 비어있을 수 없습니다.");
        }
        if (this.transactionKey != null && !this.transactionKey.equals(transactionKey)) {
            return ApplyResult.CONFLICT;
        }
        if (status.isTerminal() || status == PaymentAttemptStatus.PENDING) {
            return ApplyResult.NO_EFFECT;
        }
        this.transactionKey = transactionKey;
        this.status = PaymentAttemptStatus.PENDING;
        return ApplyResult.NO_EFFECT;
    }

    /**
     * create 응답을 받지 못했다(timeout·연결 실패·깨진 응답 등). 실패가 아니라 "모른다"이다(INV-004, D-025).
     * 발송 중(REQUESTED)일 때만 바뀌고, 이미 더 많이 알게 된 상태는 되돌리지 않는다.
     */
    public void markUnknown() {
        if (status == PaymentAttemptStatus.REQUESTED) {
            this.status = PaymentAttemptStatus.UNKNOWN;
        }
    }

    /**
     * PG가 거래를 만들기 전에 확정 거절했다(정확한 조건의 422만, docs/week5/pg-contract.md 2번).
     * 효과 0건이 보장된 유일한 생성 응답이라 바로 FAILED로 종결한다. 거래가 없으므로 transactionKey를 꾸며 넣지 않는다.
     */
    public ApplyResult rejectBeforeCreate() {
        if (status != PaymentAttemptStatus.REQUESTED) {
            return ApplyResult.NO_EFFECT;
        }
        this.status = PaymentAttemptStatus.FAILED;
        return ApplyResult.FAILED;
    }

    /**
     * PG 권위 조회로 확인한 거래를 반영한다.
     */
    public ApplyResult apply(PaymentObservation observation) {
        if (!matches(observation)) {
            return ApplyResult.CONFLICT;
        }
        if (status.isTerminal()) {
            return isSameOutcome(observation.status()) ? ApplyResult.NO_EFFECT : ApplyResult.CONFLICT;
        }
        this.transactionKey = observation.transactionKey();
        return switch (observation.status()) {
            case PENDING -> {
                this.status = PaymentAttemptStatus.PENDING;
                yield ApplyResult.NO_EFFECT;
            }
            case SUCCESS -> {
                this.status = PaymentAttemptStatus.SUCCESS;
                yield ApplyResult.SUCCEEDED;
            }
            case FAILED -> {
                this.status = PaymentAttemptStatus.FAILED;
                yield ApplyResult.FAILED;
            }
        };
    }

    private boolean matches(PaymentObservation observation) {
        return providerOrderId.equals(observation.providerOrderId())
            && amount == observation.amount()
            && (transactionKey == null || transactionKey.equals(observation.transactionKey()));
    }

    private boolean isSameOutcome(PaymentObservation.Status observed) {
        return (status == PaymentAttemptStatus.SUCCESS && observed == PaymentObservation.Status.SUCCESS)
            || (status == PaymentAttemptStatus.FAILED && observed == PaymentObservation.Status.FAILED);
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getAttemptKey() {
        return attemptKey;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getTransactionKey() {
        return transactionKey;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }

    public ZonedDateTime getSendStartedAt() {
        return sendStartedAt;
    }
}
