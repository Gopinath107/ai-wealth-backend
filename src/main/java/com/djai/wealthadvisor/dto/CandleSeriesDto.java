package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CandleSeriesDto(
        String instrumentKey,
        String unit,
        int interval,
        String order,
        int limit,
        LocalDateTime asOf,
        List<CandleDto> candles
) {}
