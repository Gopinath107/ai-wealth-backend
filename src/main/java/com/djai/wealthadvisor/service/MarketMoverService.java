package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketMoverDto;

import java.util.List;

public interface MarketMoverService {
    List<MarketMoverDto> topGainers(int limit);
    List<MarketMoverDto> topLosers(int limit);
}
