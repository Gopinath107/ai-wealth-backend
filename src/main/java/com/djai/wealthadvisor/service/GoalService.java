package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.GoalDto;

import java.util.List;

public interface GoalService {
    List<GoalDto> list(Long userId);

    GoalDto add(GoalDto req);

    GoalDto update(String goalKey, GoalDto req);

    void delete(String goalKey, Long userId);
}
