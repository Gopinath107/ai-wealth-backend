package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.WatchlistDto;

import java.util.List;

public interface WatchlistService {
    WatchlistDto add(WatchlistDto req);
    List<WatchlistDto> list(Long userId);
    void delete(Long id, Long userId);
}
