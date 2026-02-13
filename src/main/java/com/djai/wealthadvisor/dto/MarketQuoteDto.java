package com.djai.wealthadvisor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class MarketQuoteDto {
    private String instrumentKey;
    private String tradingSymbol;
    private String name;
    private String exchange;

    private double ltp;
    private double prevClose;
    private double change;
    private double changePercent;

    private LocalDateTime asOf;
}
