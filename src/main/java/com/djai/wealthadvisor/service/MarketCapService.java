package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketCapDto;

import java.util.List;
import java.util.Map;

public interface MarketCapService {
    MarketCapDto getMarketCap(String instrumentKey);
    Map<String, MarketCapDto> getMarketCaps(List<String> instrumentKeys);
}
