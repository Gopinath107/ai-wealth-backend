package com.djai.wealthadvisor.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "investment_goal", schema = "dj")
@Data
public class InvestmentGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Frontend-visible UUID (maps to frontend "id" field) */
    @Column(name = "goal_key", nullable = false, unique = true, length = 64)
    private String goalKey;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * One of: Retirement, Purchase, Savings, Emergency Fund, Education, Vacation,
     * House Down Payment, Wedding, Debt Payoff, Custom
     */
    @Column(nullable = false, length = 50)
    private String type;

    @Column(name = "target_amount", nullable = false)
    private Double targetAmount;

    @Column(name = "current_amount", nullable = false)
    private Double currentAmount;

    @Column(name = "monthly_contribution")
    private Double monthlyContribution;

    @Column(length = 20)
    private String deadline;

    @Column(name = "risk_profile", length = 30)
    private String riskProfile;

    @Column(name = "projected_completion_date", length = 30)
    private String projectedCompletionDate;

    /** JSON array: [{ "assetClass": "Stocks", "percentage": 60 }, ...] */
    @Column(name = "allocation_strategy_json", columnDefinition = "TEXT")
    private String allocationStrategyJson;

    /** JSON array: ["milestone1", "milestone2", ...] */
    @Column(name = "milestones_json", columnDefinition = "TEXT")
    private String milestonesJson;

    /**
     * JSON array: [{ "id": "c1", "amount": 500, "date": "2026-01-15", "note": "..."
     * }, ...]
     */
    @Column(name = "contributions_json", columnDefinition = "TEXT")
    private String contributionsJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
