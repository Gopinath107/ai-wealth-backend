package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.LiveCandleSeriesDto;

public interface LiveCandleService {
    LiveCandleSeriesDto getLiveCandles(String instrumentKey, String unit, int interval, int window);
}
