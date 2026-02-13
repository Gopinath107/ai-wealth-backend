package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

public record MarketIndexDto(
        String name,
        double ltp,
        double change,
        double changePercent,
        LocalDateTime asOf
) {}
