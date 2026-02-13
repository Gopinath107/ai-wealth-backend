package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.GoalDto;

import java.util.List;
import java.util.Map;

public interface GoalAiService {
    GoalDto generatePlan(String userPrompt);

    GoalDto generatePlanFromMessages(List<Map<String, String>> messages, Long userId);
}
