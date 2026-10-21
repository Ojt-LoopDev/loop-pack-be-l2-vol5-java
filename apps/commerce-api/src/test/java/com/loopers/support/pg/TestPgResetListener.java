package com.loopers.support.pg;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * 모든 Spring 테스트 메서드 전에 테스트 PG를 기본 응답(전부 202 PENDING)으로 되돌리고 받은 요청 기록을 비운다.
 * 앞 테스트가 더한 stub이나 요청 수가 다음 테스트에 남지 않게 한다. 순서는 RedisCacheCleanUpListener 옆이다.
 * 등록: src/test/resources/META-INF/spring.factories
 */
public class TestPgResetListener extends AbstractTestExecutionListener {

    private static final int ORDER = 5_600;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        TestPg.reset();
    }
}
