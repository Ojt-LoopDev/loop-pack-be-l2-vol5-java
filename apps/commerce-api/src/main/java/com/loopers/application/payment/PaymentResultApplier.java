package com.loopers.application.payment;

import com.loopers.application.product.ProductCacheInvalidator;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.ApplyResult;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/**
 * 결과 반영(TX B) — 결과가 들어오는 모든 입구(confirm·reconcile·callback 워커·스케줄러·관리자)가 지나는 경계다
 * (docs/week5/design.md 3번). 결제 시도·주문·예약을 한 트랜잭션에서 바꾼다(INV-001).
 *
 * - 락 순서: 주문 → 결제 시도 → 상품(id 오름차순)(D-002).
 * - 전이 판단은 PaymentAttempt가 하고, 여기서는 전이가 일어났을 때만 효과를 낸다(INV-002, D-001).
 * - PG 호출은 하지 않는다. 호출자가 트랜잭션 밖에서 조회한 결과를 넘긴다.
 */
@RequiredArgsConstructor
@Component
public class PaymentResultApplier {

    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ProductService productService;
    private final ProductCacheInvalidator productCacheInvalidator;

    /** PG 권위 조회로 확인한 거래를 반영한다. */
    @Transactional
    public ApplyResult apply(Long orderId, PaymentObservation observation) {
        Locked locked = lock(orderId);
        ApplyResult result = locked.attempt().apply(observation);
        applyEffect(locked.order(), locked.attempt(), result);
        return result;
    }

    /** 정확한 조건의 422 — 효과 0건이 확정된 거절이라 바로 FAILED로 종결하고 예약을 돌려준다. */
    @Transactional
    public ApplyResult rejectBeforeCreate(Long orderId) {
        Locked locked = lock(orderId);
        ApplyResult result = locked.attempt().rejectBeforeCreate();
        applyEffect(locked.order(), locked.attempt(), result);
        return result;
    }

    /** create 202 PENDING — transactionKey만 기록한다. 주문·재고는 바뀌지 않는다. */
    @Transactional
    public ApplyResult recordAccepted(Long orderId, String transactionKey) {
        return lock(orderId).attempt().accept(transactionKey);
    }

    /** create 응답을 받지 못했다 — 실패가 아니라 "모른다"(INV-004). */
    @Transactional
    public void recordUnknown(Long orderId) {
        lock(orderId).attempt().markUnknown();
    }

    private Locked lock(Long orderId) {
        Order order = orderService.getOrderForUpdate(orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
        return new Locked(order, attempt);
    }

    private void applyEffect(Order order, PaymentAttempt attempt, ApplyResult result) {
        switch (result) {
            case SUCCEEDED -> order.completePayment(attempt.getAmount());
            case FAILED -> {
                order.failPayment();
                // 예약 반환 — 지금 값에 더한다(INV-006). 상품 락은 주문·시도 다음, id 오름차순.
                order.getItems().stream()
                    .sorted(Comparator.comparing(OrderItem::getProductId))
                    .forEach(item -> productService.increaseStock(item.getProductId(), item.getQuantity()));
                productCacheInvalidator.productsChanged(order.getItems().stream()
                    .map(OrderItem::getProductId)
                    .toList());
            }
            case NO_EFFECT, CONFLICT -> {
                // 상태를 바꾸지 않는다(INV-003). 충돌을 기록으로 남기는 일(D-010·D-011)은 아직 연결하지 않았다.
            }
        }
    }

    private record Locked(Order order, PaymentAttempt attempt) {}
}
