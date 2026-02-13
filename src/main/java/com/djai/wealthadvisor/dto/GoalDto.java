package com.djai.wealthadvisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalDto {

    // ---- Identity ----
    private String id; // frontend UUID (= entity.goalKey)
    private Long userId;

    // ---- Core fields ----
    private String name;
    private String description;
    private String type; // Retirement | Purchase | Savings | ...
    private Double targetAmount;
    private Double currentAmount;
    private Double monthlyContribution;
    private String deadline;
    private String riskProfile;
    private String projectedCompletionDate;

    // ---- AI Clarification ----
    private String question; // If set, AI is asking for more info
    private List<String> suggestions; // Clickable suggestion chips

    // ---- Nested objects (JSON in DB) ----
    private List<AllocationEntry> allocationStrategy;
    private List<String> milestones;
    private List<ContributionEntry> contributions;

    // ---- Inner records ----

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllocationEntry {
        private String assetClass;
        private Double percentage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContributionEntry {
        private String id;
        private Double amount;
        private String date;
        private String note;
    }
}
