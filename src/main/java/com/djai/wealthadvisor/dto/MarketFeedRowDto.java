package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

public record MarketFeedRowDto(
        String instrumentKey,
        String tradingSymbol,
        String name,
        String exchange,
        double ltp,
        double prevClose,
        double change,
        double changePercent,
        Long marketCap,
        String currency, 
        LocalDateTime asOf
) {}
