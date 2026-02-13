package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketQuoteDto;
import java.util.Map;

public interface BenchmarkService {
    Map<String, MarketQuoteDto> getBenchmarks();
}
