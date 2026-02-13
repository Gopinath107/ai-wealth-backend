package com.djai.wealthadvisor.dto;

public record CandleDto(
        String timestamp,     // "2025-01-12T15:15:00+05:30"
        double open,
        double high,
        double low,
        double close,
        long volume,
        long openInterest
) {}
