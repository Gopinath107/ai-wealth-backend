package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

public record MarketMoverDto(
        String instrumentKey,
        String tradingSymbol,
        String name,
        String exchange,
        double ltp,
        double prevClose,
        double change,
        double changePercent,
        LocalDateTime asOf
) {}
