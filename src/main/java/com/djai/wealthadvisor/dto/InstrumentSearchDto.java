package com.djai.wealthadvisor.dto;

public record InstrumentSearchDto(
        String instrumentKey,
        String tradingSymbol,
        String name,
        String exchange,
        String segment,
        String instrumentType
) {}
