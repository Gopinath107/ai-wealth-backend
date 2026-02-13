package com.djai.wealthadvisor.repository;

import com.djai.wealthadvisor.entity.InvestmentGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvestmentGoalRepository extends JpaRepository<InvestmentGoal, Long> {

    List<InvestmentGoal> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<InvestmentGoal> findByGoalKeyAndUserId(String goalKey, Long userId);

    Optional<InvestmentGoal> findByGoalKey(String goalKey);

    @Modifying
    @Transactional
    long deleteByGoalKeyAndUserId(String goalKey, Long userId);
}
