package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

public record MarketCapDto(
        String instrumentKey,
        String yahooSymbol,
        Long marketCap,
        String currency,
        LocalDateTime asOf
) {}
