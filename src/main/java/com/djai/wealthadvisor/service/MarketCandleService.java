package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.CandleSeriesDto;

public interface MarketCandleService {
    CandleSeriesDto getIntradayCandles(String instrumentKey, String unit, int interval, int limit, String order);

    CandleSeriesDto getHistoricalCandles(String instrumentKey, String unit, int interval,
            String fromDate, String toDate, int limit, String order);
}