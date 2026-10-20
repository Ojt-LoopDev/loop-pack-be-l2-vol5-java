package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttempt, Long> {

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaymentAttempt a WHERE a.orderId = :orderId")
    Optional<PaymentAttempt> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    Optional<PaymentAttempt> findByAttemptKey(String attemptKey);

    Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId);

    Optional<PaymentAttempt> findByTransactionKey(String transactionKey);
}
