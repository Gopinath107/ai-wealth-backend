package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

public record LivePriceDto(
        String instrumentKey,
        double ltp,
        double change,
        double changePercent,
        LocalDateTime asOf
) {}
