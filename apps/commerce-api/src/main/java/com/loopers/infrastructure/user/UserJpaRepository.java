package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserJpaRepository extends JpaRepository<UserModel, Long> {

    /**
     * 읽고-검증-저장 없이 DB에서 원자적으로 잔액을 더한다 — 동시 충전 간 lost-update를 막는다.
     * (docs/week2/design.md 5번 섹션 "증가(Point.charge()) → 원자적 UPDATE" 참고)
     */
    @Modifying
    @Transactional
    @Query("UPDATE UserModel u SET u.point.balance = u.point.balance + :amount WHERE u.id = :id")
    int chargePoint(@Param("id") Long id, @Param("amount") long amount);
}
