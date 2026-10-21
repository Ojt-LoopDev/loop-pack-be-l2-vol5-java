package com.loopers.interfaces.api.order;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderV1ApiE2ETest {

    private static final String ORDERS_URL = "/api/v1/orders";
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER_RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderListResponse>> ORDER_LIST_RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final UserService userService;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public OrderV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        UserJpaRepository userJpaRepository,
        OrderJpaRepository orderJpaRepository,
        UserService userService,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.userService = userService;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ProductModel createProduct(long price, int stock) {
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
        return productJpaRepository.save(new ProductModel("에어맥스", price, brand.getId(), stock));
    }

    private Long createUser() {
        return userJpaRepository.save(new UserModel()).getId();
    }

    private Long createUserWithBalance(long balance) {
        Long userId = createUser();
        userService.chargePoint(userId, balance);
        return userId;
    }

    private HttpEntity<OrderV1Dto.CreateRequest> createRequest(Long userId, List<OrderV1Dto.CreateRequest.ItemRequest> items) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(new OrderV1Dto.CreateRequest(items), headers);
    }

    private HttpEntity<Void> requestWithUser(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> createOrder(Long userId, List<OrderV1Dto.CreateRequest.ItemRequest> items) {
        return testRestTemplate.exchange(ORDERS_URL, HttpMethod.POST, createRequest(userId, items), ORDER_RESPONSE_TYPE);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId) {
        return confirmOrder(userId, orderId, "key-" + orderId);
    }

    private ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> confirmOrder(Long userId, Long orderId, String attemptKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-USER-ID", String.valueOf(userId));
        headers.setContentType(MediaType.APPLICATION_JSON);
        OrderV1Dto.ConfirmRequest body = new OrderV1Dto.ConfirmRequest(attemptKey, "TEST", "0000-0000-0000-0000");
        return testRestTemplate.exchange(
            ORDERS_URL + "/" + orderId + "/confirm", HttpMethod.POST, new HttpEntity<>(body, headers), ORDER_RESPONSE_TYPE
        );
    }

    @DisplayName("POST /api/v1/orders")
    @Nested
    class Create {
        @DisplayName("유효한 품목으로 요청하면, 201과 DRAFT 주문을 반환한다.")
        @Test
        void returns201_whenItemsAreValid() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().data().status().name()).isEqualTo("DRAFT");
            assertThat(response.getBody().data().items()).hasSize(1);
        }

        @DisplayName("품목이 하나도 없으면, 400을 반환한다.")
        @Test
        void returns400_whenItemsAreEmpty() {
            // arrange
            Long userId = createUser();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, List.of());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("존재하지 않는 상품을 참조하면, 404를 반환한다.")
        @Test
        void returns404_whenProductDoesNotExist() {
            // arrange
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(999L, 1));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("삭제된 상품을 참조하면, 404를 반환한다.")
        @Test
        void returns404_whenProductIsDeleted() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            product.delete();
            productJpaRepository.saveAndFlush(product);
            Long userId = createUser();
            var items = List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("같은 상품이 여러 품목으로 들어오면, 수량을 합쳐 하나로 반환한다.")
        @Test
        void mergesItems_whenSameProductAppearsMultipleTimes() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            var items = List.of(
                new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2),
                new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 3)
            );

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = createOrder(userId, items);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().data().items()).hasSize(1);
            assertThat(response.getBody().data().items().get(0).quantity()).isEqualTo(5);
        }
    }

    @DisplayName("POST /api/v1/orders/{orderId}/confirm")
    @Nested
    class Confirm {
        // 5주차: 확정은 PG 결제 시작이다. 테스트 PG가 생성 요청을 202 PENDING으로 받으므로 결제는 진행 중으로 끝난다.
        @DisplayName("소유자가 DRAFT 주문을 확정하면, 202와 결제 진행 상태·조회 경로를 반환하고 재고만 확보하며 포인트는 그대로다.")
        @Test
        void startsPayment_whenOwnerConfirmsDraftOrder() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            OrderV1Dto.OrderResponse data = response.getBody().data();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.SUCCESS);
            assertThat(data.status().name()).isEqualTo("PAYMENT_PENDING");
            assertThat(data.paymentMethod().name()).isEqualTo("PG");
            assertThat(data.payment().status().name()).isEqualTo("PENDING");
            assertThat(data.payment().amount()).isEqualTo(2000L);
            assertThat(data.paidAmount()).isNull();
            assertThat(data.links().order()).isEqualTo("/api/v1/orders/" + orderId);
            assertThat(data.links().reconcile()).isEqualTo("/api/v1/orders/" + orderId + "/payment/reconcile");
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(8);
            assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isEqualTo(5000L);
        }

        @DisplayName("결제가 그 자리에서 끝나면(효과 없는 거절), 200과 PAYMENT_FAILED를 반환하고 reconcile 경로를 주지 않는다.")
        @Test
        void returns200_whenPaymentEndsImmediately() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)))
                .getBody().data().id();
            TestPg.server().stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"" + String.format("LP-ORD-%010d", orderId)
                        + "\",\"amount\":2000,\"transactionCreated\":false}")));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().status().name()).isEqualTo("PAYMENT_FAILED");
            assertThat(response.getBody().data().payment().status().name()).isEqualTo("FAILED");
            assertThat(response.getBody().data().links().reconcile()).isNull();
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(10);
        }

        @DisplayName("소유자가 아니면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long ownerId = createUserWithBalance(5000L);
            Long otherId = createUser();
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(ownerId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(otherId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("이미 결제를 시작한 주문에 다른 결제 시도 키로 요청하면, 409를 반환한다.")
        @Test
        void returns409_whenOrderIsAlreadyConfirmed() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();
            confirmOrder(userId, orderId, "key-A");

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId, "key-B");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("같은 결제 시도 키로 다시 요청하면(응답을 잃은 재시도), PG를 다시 부르지 않고 저장된 상태를 202로 반환한다.")
        @Test
        void returnsStoredState_whenSameKeyIsRetried() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();
            confirmOrder(userId, orderId, "key-A");

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId, "key-A");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody().data().payment().attemptKey()).isEqualTo("key-A");
            TestPg.server().verify(exactly(1), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
        }

        @DisplayName("결제 시도 키가 없으면, 400을 반환하고 PG를 부르지 않는다.")
        @Test
        void returns400_whenAttemptKeyIsMissing() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId, null);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            TestPg.server().verify(exactly(0), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
        }

        @DisplayName("재고가 부족하면, 400을 반환한다.")
        @Test
        void returns400_whenStockIsInsufficient() {
            // arrange
            ProductModel product = createProduct(1000L, 1);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        // 4주차까지는 포인트 부족이면 400이었다. 5주차부터 확정은 포인트를 차감하지 않으므로(과제: 포인트 차감 0회) 잔액과 상관없다.
        @DisplayName("포인트 잔액이 0이어도, 결제를 시작하고(202) 재고를 확보하며 잔액은 그대로다.")
        @Test
        void startsPayment_regardlessOfPointBalance() {
            // arrange
            ProductModel productA = createProduct(1000L, 5);
            ProductModel productB = createProduct(1000L, 5);
            Long userId = createUser(); // 잔액 0
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created = createOrder(userId, List.of(
                new OrderV1Dto.CreateRequest.ItemRequest(productA.getId(), 1),
                new OrderV1Dto.CreateRequest.ItemRequest(productB.getId(), 1)
            ));
            Long orderId = created.getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(productJpaRepository.findById(productA.getId()).orElseThrow().getRemainingStock()).isEqualTo(4);
            assertThat(productJpaRepository.findById(productB.getId()).orElseThrow().getRemainingStock()).isEqualTo(4);
            assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isZero();
        }

        @DisplayName("생성 이후 상품이 삭제됐으면, 404를 반환한다.")
        @Test
        void returns404_whenProductWasDeletedAfterCreation() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> created =
                createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));
            Long orderId = created.getBody().data().id();
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response = confirmOrder(userId, orderId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("재고 1개짜리 상품에 두 주문이 동시에 확정을 시도하면, 하나만 성공한다.")
        @Test
        void onlyOneSucceeds_whenTwoOrdersConfirmConcurrentlyWithStockOfOne() throws InterruptedException {
            // arrange
            ProductModel product = createProduct(1000L, 1);
            Long userA = createUserWithBalance(5000L);
            Long userB = createUserWithBalance(5000L);
            Long orderAId = createOrder(userA, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();
            Long orderBId = createOrder(userB, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            ExecutorService executorService = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failureCount = new AtomicInteger();

            // act
            executorService.submit(() -> {
                try {
                    if (confirmOrder(userA, orderAId).getStatusCode() == HttpStatus.ACCEPTED) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            executorService.submit(() -> {
                try {
                    if (confirmOrder(userB, orderBId).getStatusCode() == HttpStatus.ACCEPTED) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            latch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);

            // assert
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(failureCount.get()).isEqualTo(1);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(0);
        }

        // 더블클릭은 같은 결제 시도 키로 두 번 보낸다. 4주차에는 하나가 409였지만, 이제 같은 키는 같은 요청이라
        // 둘 다 저장된 같은 상태(202)를 받는다(D-015). 효과는 여전히 한 번이다 — 재고 확보 한 번, PG 생성 요청 한 번.
        @DisplayName("같은 주문에 같은 키로 확정 요청이 동시에 두 번 겹치면(더블클릭), 둘 다 202이고 재고 확보와 PG 생성 요청은 한 번뿐이다.")
        @Test
        void startsOnce_whenSameOrderIsConfirmedConcurrentlyTwice() throws InterruptedException {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUserWithBalance(5000L);
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 2)))
                .getBody().data().id();

            ExecutorService executorService = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);
            AtomicInteger acceptedCount = new AtomicInteger();

            // act
            Runnable confirmAttempt = () -> {
                try {
                    HttpStatusCode status = confirmOrder(userId, orderId, "double-click").getStatusCode();
                    if (status == HttpStatus.ACCEPTED) {
                        acceptedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            };
            executorService.submit(confirmAttempt);
            executorService.submit(confirmAttempt);
            latch.await(10, TimeUnit.SECONDS);
            executorService.shutdown();
            executorService.awaitTermination(10, TimeUnit.SECONDS);

            // assert
            assertThat(acceptedCount.get()).isEqualTo(2);
            assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getRemainingStock()).isEqualTo(8);
            assertThat(userJpaRepository.findById(userId).orElseThrow().getPoint().getBalance()).isEqualTo(5000L);
            TestPg.server().verify(exactly(1), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
        }
    }

    @DisplayName("GET /api/v1/orders")
    @Nested
    class GetMyOrders {
        @DisplayName("요청자의 주문 목록을 200으로 반환한다.")
        @Test
        void returns200_withMyOrders() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)));

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderListResponse>> response =
                testRestTemplate.exchange(ORDERS_URL, HttpMethod.GET, requestWithUser(userId), ORDER_LIST_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().orders()).hasSize(1);
        }
    }

    @DisplayName("GET /api/v1/orders/{orderId}")
    @Nested
    class GetMyOrder {
        @DisplayName("소유자가 조회하면, 200과 주문 상세를 반환한다.")
        @Test
        void returns200_whenOwnerRequests() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long userId = createUser();
            Long orderId = createOrder(userId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ORDERS_URL + "/" + orderId, HttpMethod.GET, requestWithUser(userId), ORDER_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().id()).isEqualTo(orderId);
        }

        @DisplayName("소유자가 아니면, 404를 반환한다.")
        @Test
        void returns404_whenRequesterIsNotOwner() {
            // arrange
            ProductModel product = createProduct(1000L, 10);
            Long ownerId = createUser();
            Long otherId = createUser();
            Long orderId = createOrder(ownerId, List.of(new OrderV1Dto.CreateRequest.ItemRequest(product.getId(), 1)))
                .getBody().data().id();

            // act
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ORDERS_URL + "/" + orderId, HttpMethod.GET, requestWithUser(otherId), ORDER_RESPONSE_TYPE);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
