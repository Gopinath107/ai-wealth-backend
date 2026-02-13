package com.djai.wealthadvisor.dto;

public record LiveCandleDto(
        String time,   // ISO like "2026-01-22T11:55:00"
        double open,
        double high,
        double low,
        double close,
        long volume
) {}
