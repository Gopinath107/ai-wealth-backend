package com.djai.wealthadvisor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStrategyDto {

    // ---- Request fields ----
    @NotBlank(message = "instrumentKey is required")
    private String instrumentKey;
    private String tradingSymbol;
    private String name;

    // ---- Quote snapshot ----
    private String exchange;
    private LocalDateTime asOf;

    private Double ltp;
    private Double prevClose;
    private Double change;
    private Double changePercent;

    // ---- Strategy output ----
    private String recommendation;     // BUY / SELL / HOLD
    private Integer confidence;        // 0-100
    private String horizon;            // e.g., Long-term (3–12 months)
    private String riskLevel;          // Low / Medium / High
    private String personalizedStrategy;
    private List<String> keyDrivers;

    // ---- Technical metrics (flat) ----
    private Double sma20;
    private Double sma50;
    private Double sma200;

    private Double rsi14;

    private Double macd;
    private Double macdSignal;
    private Double macdHistogram;

    private Double support20;
    private Double resistance20;
    private Double volatility20Pct;

    // ---- Analyst metrics (flat) ----
    private Boolean analystAvailable;
    private Integer analystCount;

    private Integer strongBuy;
    private Integer buy;
    private Integer hold;
    private Integer sell;
    private Integer strongSell;

    private Double targetMean;
    private Double targetHigh;
    private Double targetLow;
    private Double upsidePercent;
    private String analystPeriod;
}
