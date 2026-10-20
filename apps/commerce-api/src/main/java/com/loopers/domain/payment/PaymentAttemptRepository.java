package com.loopers.domain.payment;

import java.util.Optional;

public interface PaymentAttemptRepository {

    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    /**
     * 비관적 락으로 조회한다 — 결과 반영(TX B)이 겹칠 때 전이를 한 번만 일으키기 위해서다(INV-002).
     * 락 순서는 주문 → 결제 시도 → 상품이므로, 호출자는 주문을 먼저 잠근 뒤 이 메서드를 부른다(docs/week5/design.md D-002).
     */
    Optional<PaymentAttempt> findByOrderIdForUpdate(Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    /**
     * 잠그지 않고 읽는다 — callback 워커가 주문 번호(orderId)를 알아낼 때 쓴다. orderId는 생성 뒤 바뀌지 않으므로
     * 잠그지 않아도 안전하다(INV-007). 상태는 주문·시도를 잠근 뒤 다시 읽어 판단한다.
     */
    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    /**
     * findByProviderOrderId와 같은 이유로 잠그지 않는다.
     */
    Optional<PaymentAttempt> findByTransactionKey(String transactionKey);
}
