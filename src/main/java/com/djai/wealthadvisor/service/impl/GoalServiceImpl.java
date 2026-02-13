package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.entity.InvestmentGoal;
import com.djai.wealthadvisor.repository.InvestmentGoalRepository;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.service.GoalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoalServiceImpl implements GoalService {

    private final InvestmentGoalRepository goalRepo;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    // ────────────────────────── LIST ──────────────────────────

    @Override
    public List<GoalDto> list(Long userId) {
        if (userId == null)
            throw new IllegalArgumentException("userId is required");

        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + userId));

        List<InvestmentGoal> entities = goalRepo.findByUserIdOrderByCreatedAtDesc(userId);
        List<GoalDto> out = new ArrayList<>(entities.size());
        for (InvestmentGoal e : entities) {
            out.add(toDto(e));
        }
        return out;
    }

    // ────────────────────────── ADD ───────────────────────────

    @Override
    public GoalDto add(GoalDto req) {
        if (req == null)
            throw new IllegalArgumentException("Request body is required");
        if (req.getUserId() == null)
            throw new IllegalArgumentException("userId is required");

        userRepository.findById(req.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + req.getUserId()));

        InvestmentGoal entity = new InvestmentGoal();
        entity.setUserId(req.getUserId());

        // Generate goalKey if frontend didn't supply one
        String key = (req.getId() != null && !req.getId().isBlank()) ? req.getId() : UUID.randomUUID().toString();
        entity.setGoalKey(key);

        applyDtoToEntity(req, entity);
        entity = goalRepo.save(entity);
        return toDto(entity);
    }

    // ────────────────────────── UPDATE ────────────────────────

    @Override
    public GoalDto update(String goalKey, GoalDto req) {
        if (goalKey == null || goalKey.isBlank())
            throw new IllegalArgumentException("goalKey is required");
        if (req == null)
            throw new IllegalArgumentException("Request body is required");
        if (req.getUserId() == null)
            throw new IllegalArgumentException("userId is required");

        InvestmentGoal entity = goalRepo.findByGoalKeyAndUserId(goalKey, req.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("Goal not found: " + goalKey));

        applyDtoToEntity(req, entity);
        entity = goalRepo.save(entity);
        return toDto(entity);
    }

    // ────────────────────────── DELETE ────────────────────────

    @Override
    @Transactional
    public void delete(String goalKey, Long userId) {
        if (goalKey == null || goalKey.isBlank())
            throw new IllegalArgumentException("goalKey is required");
        if (userId == null)
            throw new IllegalArgumentException("userId is required");

        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + userId));

        long deleted = goalRepo.deleteByGoalKeyAndUserId(goalKey, userId);
        if (deleted == 0) {
            throw new IllegalArgumentException("Goal not found for key=" + goalKey + " and userId=" + userId);
        }
    }

    // ─────────────────────── MAPPERS ─────────────────────────

    private void applyDtoToEntity(GoalDto dto, InvestmentGoal entity) {
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setType(dto.getType() != null ? dto.getType() : "Savings");
        entity.setTargetAmount(dto.getTargetAmount() != null ? dto.getTargetAmount() : 0.0);
        entity.setCurrentAmount(dto.getCurrentAmount() != null ? dto.getCurrentAmount() : 0.0);
        entity.setMonthlyContribution(dto.getMonthlyContribution());
        entity.setDeadline(dto.getDeadline());
        entity.setRiskProfile(dto.getRiskProfile());
        entity.setProjectedCompletionDate(dto.getProjectedCompletionDate());

        // Serialize nested arrays to JSON
        entity.setAllocationStrategyJson(toJson(dto.getAllocationStrategy()));
        entity.setMilestonesJson(toJson(dto.getMilestones()));
        entity.setContributionsJson(toJson(dto.getContributions()));
    }

    private GoalDto toDto(InvestmentGoal entity) {
        return GoalDto.builder()
                .id(entity.getGoalKey())
                .userId(entity.getUserId())
                .name(entity.getName())
                .description(entity.getDescription())
                .type(entity.getType())
                .targetAmount(entity.getTargetAmount())
                .currentAmount(entity.getCurrentAmount())
                .monthlyContribution(entity.getMonthlyContribution())
                .deadline(entity.getDeadline())
                .riskProfile(entity.getRiskProfile())
                .projectedCompletionDate(entity.getProjectedCompletionDate())
                .allocationStrategy(fromJson(entity.getAllocationStrategyJson(),
                        new TypeReference<List<GoalDto.AllocationEntry>>() {
                        }))
                .milestones(fromJson(entity.getMilestonesJson(),
                        new TypeReference<List<String>>() {
                        }))
                .contributions(fromJson(entity.getContributionsJson(),
                        new TypeReference<List<GoalDto.ContributionEntry>>() {
                        }))
                .build();
    }

    // ─────────────────────── JSON HELPERS ────────────────────

    private String toJson(Object obj) {
        if (obj == null)
            return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank())
            return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize JSON", e);
            return null;
        }
    }
}
