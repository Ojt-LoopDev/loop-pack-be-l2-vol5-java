# 학습 개념 정리

배우면서 처음 접했거나 헷갈렸던 개념을 여기에 남긴다. 다시 설명이 필요할 때 여기부터 확인한다.

## N+1 문제

**뭔지**: 부모 목록(예: 상품 100개)을 쿼리 1번으로 조회한 뒤, 각 부모마다 연관된 자식(예: 브랜드)을 따로따로 조회하면 총 `1(부모) + N(자식 개수)`번의 쿼리가 나가는 현상.

**왜 문제인가**: 쿼리 한 번 = DB로의 네트워크 왕복 한 번. N이 커지면 왕복 횟수만큼 지연시간이 누적된다. 이 요청 하나가 트랜잭션 커넥션 하나를 오래 붙잡고 있게 되고, 동시에 다른 요청이 몰리면 Connection Pool이 고갈되어 병목·장애로 이어질 수 있다 (원인은 "연결을 많이 써서"가 아니라 "연결 하나를 너무 오래 붙잡고 있어서").

**이 프로젝트에서 나온 맥락**: 상품 목록 조회 응답에 브랜드 정보를 포함해야 하는데, 상품마다 반복문 안에서 `brandRepository.find(brandId)`를 호출하면 이 문제가 발생한다.

## Fetch Join / 일괄 조회(batch loading)

**Fetch Join**: JPA에서 부모가 자식을 **객체로 참조**하고 있을 때(`@ManyToOne`, `@OneToMany` 등 연관관계 매핑이 있을 때) `JOIN FETCH`로 한 번의 SQL JOIN에 묶어서 가져오는 문법. N+1을 1번의 쿼리로 줄인다.

**우리 프로젝트에선 왜 다르게 접근했나**: `Product`가 `Brand`를 객체로 참조하지 않고 `brandId`만 갖기로 했다(서로 다른 aggregate라서 ID로만 참조). 연관관계 매핑 자체가 없으니 `JOIN FETCH` 문법을 쓸 수 없다.

**대신 쓴 패턴 — 일괄 조회**: 상품 목록에서 `brandId`를 모아 중복 제거한 뒤, `brandRepository.findAllById(ids)`를 **한 번만** 호출한다 (Spring Data JPA가 `WHERE id IN (...)`로 변환). 결과적으로 상품 조회 1번 + 브랜드 조회 1번 = 2번으로 끝나고, 리스트 크기가 커져도 쿼리 횟수는 늘지 않는다. `Order`-`OrderItem`처럼 같은 aggregate 안의 진짜 객체 연관관계라면, 그때는 `JOIN FETCH`를 직접 쓰게 될 것이다.

## ArchUnit

**뭔지**: 컴파일된 클래스(bytecode)를 분석해서 "어느 패키지가 어느 패키지에 의존해도 되는지"를 JUnit 테스트로 검사하는 라이브러리. 일반 테스트가 동작(값)을 검사한다면, ArchUnit은 구조(의존 방향)를 검사한다.

**왜 필요한가**: 컴파일러는 문법·타입만 확인하고 "이 의존이 허용되는가"는 신경 쓰지 않는다. `domain`이 `interfaces`를 import해도 컴파일은 멀쩡히 된다 — 이 빈틈을 ArchUnit이 메운다.

**이 프로젝트에서 쓴 곳**: `apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java`. `noClasses().that().resideInAPackage("..domain..").should().dependOnClassesThat().resideInAnyPackage(...)` 형태로, layered architecture의 의존 규칙(2번 문서)을 테스트로 강제한다. 누군가 실수로 `domain`에서 `interfaces`를 import하면 컴파일은 되더라도 이 테스트가 실패한다.

## 주요 Spring/Lombok 애너테이션 (Controller 기준)

| 애너테이션 | 역할 |
|---|---|
| `@RestController` | 이 클래스는 HTTP 요청을 처리하고, 반환값을 그대로 JSON 응답 본문으로 바꾼다 |
| `@RequestMapping("/api/v1/brands")` | 클래스 안 모든 엔드포인트에 공통으로 붙는 URL 경로(prefix) |
| `@GetMapping("/{brandId}")` | GET + 이 경로로 오는 요청을 이 메서드에 연결 |
| `@PathVariable` | URL 경로 변수(`{brandId}`)의 값을 메서드 파라미터로 꺼냄 |
| `@RequiredArgsConstructor` | (Lombok) `final` 필드를 받는 생성자를 자동 생성 — Spring이 이 생성자로 의존성을 주입할 수 있게 함 |
| `@Override` | (자바 표준) 인터페이스/부모 메서드를 재정의했음을 컴파일러에 알림 |

## DTO (Data Transfer Object)

**뭔지**: 계층 사이(특히 외부 API)에 데이터를 주고받기 위한 전용 객체 (`BrandV1Dto.BrandResponse` 등).

**왜 도메인 모델을 그대로 반환하지 않는가**: 도메인 모델은 업무 규칙용, DTO는 "이 API가 밖에 보여줄 모양"용으로 목적이 다르다. 분리 없이 도메인 모델을 그대로 응답하면, 도메인 모델 내부가 바뀔 때마다 API 계약까지 깨진다. 관리자/고객마다 다른 필드를 노출하고 싶을 때도 DTO를 분리해야 대응 가능하다.

## ApiSpec / Swagger

**ApiSpec**: `BrandV1ApiSpec`처럼, 순수하게 API 문서화용 애너테이션(`@Operation`, `@Schema`)만 담은 인터페이스. Controller가 이를 `implements`해서 실제 로직을 채운다. 문서 메타데이터를 로직 코드에서 분리해 가독성을 지키는 용도.

**Swagger**: 코드의 문서화 애너테이션을 읽어서 API 문서를 자동 생성해주는 도구. 서버 실행 후 브라우저에서 엔드포인트 목록·파라미터·응답 형태를 보고 직접 요청도 날려볼 수 있다. (`springdoc-openapi-starter-webmvc-ui` 의존성으로 이미 연결돼 있음)

## 동시성 제어 — 비관적 락 / 낙관적 락 / 원자적 UPDATE

**문제**: `Stock.decrease()`처럼 "읽고 → 메모리에서 검증·차감 → 저장" 방식은, 재고 1개짜리 상품에 두 요청이 거의 동시에 오면 둘 다 "재고 충분"으로 통과해버리는 경합(race condition)이 있다. (멘토링에서 지적받음 — `docs/week2/design.md` TODO 참고)

| 방식 | 동작 | 장점 | 단점 |
|---|---|---|---|
| **비관적 락**(`SELECT ... FOR UPDATE`) | 읽는 순간 행을 잠가서, 다른 트랜잭션은 이 트랜잭션이 끝날 때까지 대기 | 확실하게 순차 처리됨 | 경합이 잦으면 대기 때문에 느려짐 |
| **낙관적 락**(`version` 컬럼) | 락 없이 진행하다가, 저장 시점에 `WHERE version=읽은버전`으로 그 사이 변경 여부 확인. 버전이 다르면 0건 영향 → 실패 | 대기 없이 빠름(충돌이 드물 때) | 충돌 잦으면 재시도 로직이 계속 실패·반복될 수 있음, 재시도 처리를 직접 구현해야 함 |
| **원자적 UPDATE**(`UPDATE ... SET x=x-1 WHERE x>=1`) | 읽고-계산하고-쓰는 걸 안 하고, DB에 조건과 함께 한 번에 맡김. 영향 행 0개면 실패 | 가장 단순·빠름, 락/재시도 불필요 | 도메인 객체(`Stock.decrease()`)의 캡슐화된 검증을 못 쓰고 조건이 SQL로 흩어짐 |
