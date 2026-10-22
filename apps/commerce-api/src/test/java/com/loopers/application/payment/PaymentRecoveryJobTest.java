package com.loopers.application.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.loopers.application.order.ConfirmPaymentCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderItemRequest;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CallbackInboxStatus;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentCallbackInbox;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentCallbackInboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * callback 수신함과 회복 작업 한 회차(docs/week5/design.md D-020, D-021, D-040~D-042). 테스트에서는 주기 실행을 꺼 두고
 * 회차를 직접 부른다. 결제는 테스트 PG 기본 응답(202 PENDING)으로 처리중 상태를 만든 뒤 시작한다.
 */
@SpringBootTest
class PaymentRecoveryJobTest {

    private static final long PRICE = 2_000L;
    private static final int INITIAL_STOCK = 10;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;

    @Autowired
    private PaymentRecoveryJob paymentRecoveryJob;

    @Autowired
    private PaymentCallbackFacade paymentCallbackFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentCallbackInboxJpaRepository paymentCallbackInboxJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Fixture(Long userId, Long orderId, Long productId) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }
    }

    /** 테스트 PG 기본 응답(202 PENDING)으로 확정해 처리중인 결제를 만든다. */
    private Fixture pending() {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        Long productId = productJpaRepository.save(new ProductModel("상품", PRICE, 1L, INITIAL_STOCK)).getId();
        Long orderId = orderFacade.createOrder(userId, List.of(new OrderItemRequest(productId, QUANTITY))).id();
        orderFacade.confirmOrder(orderId, userId, new ConfirmPaymentCommand("key-" + orderId, "TEST", "0000-0000-0000-0000"));
        return new Fixture(userId, orderId, productId);
    }

    private void callback(Fixture fixture, String status) {
        paymentCallbackFacade.receive("{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"providerOrderId\":\""
            + fixture.providerOrderId() + "\",\"status\":\"" + status + "\"}");
    }

    private void stubLookup(Fixture fixture, int status, String transactionStatus) {
        String body = transactionStatus == null ? "" : "{\"transactionKey\":\"" + fixture.transactionKey() + "\",\"orderId\":\""
            + fixture.providerOrderId() + "\",\"amount\":" + AMOUNT + ",\"status\":\"" + transactionStatus + "\"}";
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private int lookupsOf(Fixture fixture) {
        return pg.findAll(getRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))).size();
    }

    private List<PaymentCallbackInbox> inbox() {
        return paymentCallbackInboxJpaRepository.findAll();
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private int stockOf(Fixture fixture) {
        return productJpaRepository.findById(fixture.productId()).orElseThrow().getRemainingStock();
    }

    private static ZonedDateTime now() {
        return ZonedDateTime.now().plusSeconds(1);
    }

    @DisplayName("callback을 받으면, ")
    @Nested
    class Receive {
        @DisplayName("원문 그대로 수신함에 저장하고, 결제는 아직 바꾸지 않는다.")
        @Test
        void storesRawPayload_withoutTouchingPayment() {
            // arrange
            Fixture fixture = pending();

            // act
            callback(fixture, "SUCCESS");

            // assert
            List<PaymentCallbackInbox> rows = inbox();
            assertAll(
                () -> assertThat(rows).hasSize(1),
                () -> assertThat(rows.get(0).getStatus()).isEqualTo(CallbackInboxStatus.RECEIVED),
                () -> assertThat(rows.get(0).getRawPayload()).contains("\"status\":\"SUCCESS\""),
                () -> assertThat(rows.get(0).getTransactionKey()).isEqualTo(fixture.transactionKey()),
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_PENDING)
            );
        }

        @DisplayName("본문이 깨져 있어도 원문은 저장하고, 회차에서 매칭 실패로 정리한다.")
        @Test
        void storesBrokenPayload_andMarksUnmatched() {
            // act
            paymentCallbackFacade.receive("{not-json");
            paymentRecoveryJob.runOnce(now());

            // assert
            List<PaymentCallbackInbox> rows = inbox();
            assertAll(
                () -> assertThat(rows).hasSize(1),
                () -> assertThat(rows.get(0).getRawPayload()).isEqualTo("{not-json"),
                () -> assertThat(rows.get(0).getStatus()).isEqualTo(CallbackInboxStatus.UNMATCHED)
            );
        }
    }

    @DisplayName("회차를 돌면, ")
    @Nested
    class Run {
        @DisplayName("callback이 온 결제를 PG에 조회해 SUCCESS면 확정하고, 수신함 행을 완료로 둔다.")
        @Test
        void confirmsByLookup_andMarksDone() {
            // arrange
            Fixture fixture = pending();
            callback(fixture, "SUCCESS");
            stubLookup(fixture, 200, "SUCCESS");

            // act
            paymentRecoveryJob.runOnce(now());

            // assert
            PaymentCallbackInbox row = inbox().get(0);
            assertAll(
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(row.getStatus()).isEqualTo(CallbackInboxStatus.DONE),
                () -> assertThat(row.getAttemptId()).isNotNull(),
                () -> assertThat(row.getProcessedAt()).isNotNull()
            );
        }

        @DisplayName("본문은 SUCCESS라도 PG 조회가 FAILED면 조회 결과를 따른다 — 결제 실패, 재고 반환.")
        @Test
        void followsLookup_notCallbackBody() {
            // arrange
            Fixture fixture = pending();
            callback(fixture, "SUCCESS");
            stubLookup(fixture, 200, "FAILED");

            // act
            paymentRecoveryJob.runOnce(now());

            // assert
            assertAll(
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("같은 결제의 callback이 세 통 쌓여도 PG 조회는 한 번이고, 재고는 한 번만 돌려주며 세 행 모두 완료된다. (D-041, INV-002)")
        @Test
        void looksUpOncePerAttempt() {
            // arrange
            Fixture fixture = pending();
            callback(fixture, "FAILED");
            callback(fixture, "FAILED");
            callback(fixture, "FAILED");
            stubLookup(fixture, 200, "FAILED");

            // act
            paymentRecoveryJob.runOnce(now());

            // assert
            assertAll(
                () -> assertThat(lookupsOf(fixture)).isEqualTo(1),
                () -> assertThat(stockOf(fixture)).isEqualTo(INITIAL_STOCK),
                () -> assertThat(inbox()).extracting(PaymentCallbackInbox::getStatus).containsOnly(CallbackInboxStatus.DONE)
            );
        }

        @DisplayName("다른 입구가 이미 결제를 끝냈으면, PG에 묻지 않고 수신함 행만 완료로 둔다.")
        @Test
        void skipsLookup_whenAlreadyTerminal() {
            // arrange — 고객 reconcile이 먼저 끝낸 뒤 callback이 도착했다
            Fixture fixture = pending();
            stubLookup(fixture, 200, "SUCCESS");
            orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
            callback(fixture, "SUCCESS");
            pg.resetRequests();

            // act
            paymentRecoveryJob.runOnce(now());

            // assert
            assertAll(
                () -> assertThat(inbox().get(0).getStatus()).isEqualTo(CallbackInboxStatus.DONE),
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.CONFIRMED)
            );
            pg.verify(exactly(0), getRequestedFor(urlPathMatching(TestPg.PAYMENTS_PATH + ".*")));
        }

        @DisplayName("키에 맞는 결제 시도가 없으면 매칭 실패로 두고, 다음 회차에 다시 처리하지 않는다.")
        @Test
        void marksUnmatched_andNeverRetries() {
            // arrange
            paymentCallbackFacade.receive("{\"transactionKey\":\"UNKNOWN-TX\",\"providerOrderId\":\"LP-ORD-9999999999\",\"status\":\"SUCCESS\"}");

            // act
            paymentRecoveryJob.runOnce(now());
            paymentRecoveryJob.runOnce(now().plusMinutes(10));

            // assert
            assertThat(inbox().get(0).getStatus()).isEqualTo(CallbackInboxStatus.UNMATCHED);
            pg.verify(exactly(0), getRequestedFor(urlPathMatching(TestPg.PAYMENTS_PATH + ".*")));
        }
    }

    @DisplayName("PG 조회가 실패하면, (D-021, D-042)")
    @Nested
    class LookupFails {
        @DisplayName("회차마다 한 번만 조회하고 다음 회차(3분 뒤)에 다시 꺼내며, 세 번째 실패에서 포기한다. 결제는 그대로 처리중이다.")
        @Test
        void retriesNextRun_thenGivesUp() {
            // arrange
            Fixture fixture = pending();
            callback(fixture, "SUCCESS");
            stubLookup(fixture, 500, null);
            ZonedDateTime first = now();

            // act & assert — 1번째 실패: 3분 뒤로 미룬다
            paymentRecoveryJob.runOnce(first);
            PaymentCallbackInbox row = inbox().get(0);
            assertAll(
                () -> assertThat(row.getStatus()).isEqualTo(CallbackInboxStatus.RETRY),
                () -> assertThat(row.getTryCount()).isEqualTo(1),
                () -> assertThat(row.getNextTryAt().truncatedTo(ChronoUnit.SECONDS))
                    .isEqualTo(first.plusMinutes(3).truncatedTo(ChronoUnit.SECONDS)),
                () -> assertThat(lookupsOf(fixture)).isEqualTo(1)
            );

            // 아직 차례가 아니면 꺼내지 않는다
            paymentRecoveryJob.runOnce(first.plusMinutes(1));
            assertThat(lookupsOf(fixture)).isEqualTo(1);

            // 2번째, 3번째 실패
            paymentRecoveryJob.runOnce(first.plusMinutes(3));
            paymentRecoveryJob.runOnce(first.plusMinutes(6));
            PaymentCallbackInbox gaveUp = inbox().get(0);
            assertAll(
                () -> assertThat(gaveUp.getStatus()).isEqualTo(CallbackInboxStatus.GAVE_UP),
                () -> assertThat(gaveUp.getTryCount()).isEqualTo(3),
                () -> assertThat(gaveUp.getLastError()).isNotBlank(),
                () -> assertThat(lookupsOf(fixture)).isEqualTo(3),
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow().getStatus())
                    .isEqualTo(PaymentAttemptStatus.PENDING)
            );

            // 포기한 행은 더 꺼내지 않는다
            paymentRecoveryJob.runOnce(first.plusMinutes(30));
            assertThat(lookupsOf(fixture)).isEqualTo(3);
        }
    }

    @DisplayName("한 회차에서는, (D-034, D-041)")
    @Nested
    class Ordering {
        @DisplayName("발송 시작이 오래된 결제부터 조회하고, 조회 상한을 넘는 결제는 다음 회차로 넘긴다.")
        @Test
        void looksUpOldestFirst_upToLimit() {
            // arrange — older의 결제가 먼저 시작됐지만 callback은 newer가 먼저 왔다
            Fixture older = pending();
            Fixture newer = pending();
            callback(newer, "SUCCESS");
            callback(older, "SUCCESS");
            stubLookup(older, 200, "SUCCESS");
            stubLookup(newer, 200, "SUCCESS");

            // act — 상한 1건
            paymentRecoveryJob.runOnce(now(), 1);

            // assert
            assertAll(
                () -> assertThat(orderStatus(older)).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(orderStatus(newer)).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(lookupsOf(newer)).isZero()
            );

            // 다음 회차에 넘어간 결제를 처리한다
            paymentRecoveryJob.runOnce(now(), 1);
            assertThat(orderStatus(newer)).isEqualTo(OrderStatus.CONFIRMED);
            List<LoggedRequest> lookups = pg.findAll(getRequestedFor(urlPathMatching(TestPg.PAYMENTS_PATH + "/.*")));
            assertThat(lookups).extracting(LoggedRequest::getUrl)
                .containsExactly(TestPg.PAYMENTS_PATH + "/" + older.transactionKey(), TestPg.PAYMENTS_PATH + "/" + newer.transactionKey());
        }
    }
}
