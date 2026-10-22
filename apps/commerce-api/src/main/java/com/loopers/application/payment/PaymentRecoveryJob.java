package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentCallbackInbox;
import com.loopers.domain.payment.PaymentCallbackInboxRepository;
import com.loopers.domain.payment.PaymentCallbackInboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 결제 회복 작업 한 회차 — 수신함과 (7단계에서) 미종결 결제를 같은 작업으로 처리한다(docs/week5/design.md D-040).
 * 트랜잭션이 없다. 조회는 트랜잭션 밖, 반영은 TX B, 수신함 상태 기록은 짧은 TX다.
 *
 * - 처리 단위는 결제 시도다. 같은 시도의 callback 여러 통은 한 번만 조회한다(D-041).
 * - 발송 시작이 오래된 시도부터, 한 회차에 PG 조회 최대 {@link #MAX_LOOKUPS_PER_RUN}건(D-034).
 * - 시도는 수신함 본문의 키로 찾지만, PG 조회는 저장된 시도의 키로 한다 — 본문을 믿지 않는다.
 * - 조회 실패는 다음 회차에 다시, 세 번째 실패에서 포기한다(D-042). 이 경로는 조회를 재시도하지 않는다(D-028).
 */
@Slf4j
@Component
public class PaymentRecoveryJob {

    public static final int MAX_LOOKUPS_PER_RUN = 100;
    /** 한 회차에 읽는 수신함 행의 상한. 같은 시도의 callback이 겹칠 수 있어 조회 상한보다 넉넉하게 둔다. */
    private static final int INBOX_SCAN_LIMIT = 500;

    private final PaymentCallbackInboxRepository paymentCallbackInboxRepository;
    private final PaymentCallbackInboxService paymentCallbackInboxService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentReconciler paymentReconciler;
    private final Duration interval;

    public PaymentRecoveryJob(
        PaymentCallbackInboxRepository paymentCallbackInboxRepository,
        PaymentCallbackInboxService paymentCallbackInboxService,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentReconciler paymentReconciler,
        @Value("${payment.recovery.interval:PT3M}") Duration interval
    ) {
        this.paymentCallbackInboxRepository = paymentCallbackInboxRepository;
        this.paymentCallbackInboxService = paymentCallbackInboxService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentReconciler = paymentReconciler;
        this.interval = interval;
    }

    public void runOnce(ZonedDateTime now) {
        runOnce(now, MAX_LOOKUPS_PER_RUN);
    }

    /** 조회 상한을 바꿔 부를 수 있게 둔다 — 테스트가 상한 동작을 적은 건수로 확인한다. */
    void runOnce(ZonedDateTime now, int maxLookups) {
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        List<Long> unmatched = new ArrayList<>();
        for (PaymentCallbackInbox inbox : paymentCallbackInboxRepository.findDue(now, INBOX_SCAN_LIMIT)) {
            Optional<PaymentAttempt> attempt = findAttempt(inbox);
            if (attempt.isEmpty()) {
                unmatched.add(inbox.getId());
                continue;
            }
            candidates.computeIfAbsent(attempt.get().getId(), id -> new Candidate(attempt.get(), new ArrayList<>()))
                .inboxIds().add(inbox.getId());
        }
        if (!unmatched.isEmpty()) {
            paymentCallbackInboxService.markUnmatched(unmatched, now);
        }

        int lookups = 0;
        List<Candidate> ordered = candidates.values().stream()
            .sorted(Comparator.comparing((Candidate candidate) -> candidate.attempt().getSendStartedAt())
                .thenComparing(candidate -> candidate.attempt().getId()))
            .toList();
        for (Candidate candidate : ordered) {
            PaymentAttempt attempt = candidate.attempt();
            if (attempt.getStatus().isTerminal()) {
                // 이미 다른 입구(고객 reconcile 등)가 끝냈다 — 다시 물을 필요가 없다.
                paymentCallbackInboxService.markDone(candidate.inboxIds(), attempt.getId(), now);
                continue;
            }
            if (lookups >= maxLookups) {
                break;
            }
            lookups++;
            process(candidate, now);
        }
    }

    private void process(Candidate candidate, ZonedDateTime now) {
        PaymentAttempt attempt = candidate.attempt();
        PaymentReconciler.Outcome outcome;
        try {
            outcome = paymentReconciler.reconcile(
                attempt.getOrderId(), attempt.getProviderOrderId(), attempt.getTransactionKey(), LookupRetry.NONE);
        } catch (RuntimeException e) {
            // 반영 중 예상하지 못한 오류 — 이 시도만 다음 회차로 미루고 나머지 시도는 계속 처리한다.
            log.error("결제 회복 처리 실패: attemptId={}", attempt.getId(), e);
            paymentCallbackInboxService.markFailed(candidate.inboxIds(), attempt.getId(),
                e.getClass().getSimpleName(), now, now.plus(interval));
            return;
        }
        if (outcome == PaymentReconciler.Outcome.UNAVAILABLE) {
            paymentCallbackInboxService.markFailed(candidate.inboxIds(), attempt.getId(), "PG 조회 실패", now, now.plus(interval));
            return;
        }
        paymentCallbackInboxService.markDone(candidate.inboxIds(), attempt.getId(), now);
    }

    private Optional<PaymentAttempt> findAttempt(PaymentCallbackInbox inbox) {
        if (inbox.getTransactionKey() != null) {
            Optional<PaymentAttempt> byKey = paymentAttemptRepository.findByTransactionKey(inbox.getTransactionKey());
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        if (inbox.getProviderOrderId() != null) {
            return paymentAttemptRepository.findByProviderOrderId(inbox.getProviderOrderId());
        }
        return Optional.empty();
    }

    private record Candidate(PaymentAttempt attempt, List<Long> inboxIds) {}
}
