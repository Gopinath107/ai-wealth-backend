package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LiveCandleSeriesDto(
        String instrumentKey,
        String unit,
        int interval,
        int window,
        String mode,
        LocalDateTime asOf,
        List<LiveCandleDto> candles
) {}
