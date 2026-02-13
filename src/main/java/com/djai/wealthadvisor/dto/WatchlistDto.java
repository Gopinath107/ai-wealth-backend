package com.djai.wealthadvisor.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchlistDto {

    // ---- Request fields ----
    private Long userId;     // REQUIRED for your use-case
    private String query;    // "HINDUNILVR" or "Reliance" or "NSE_EQ|INE030A01027"

    // ---- Saved Watchlist item fields ----
    private Long id;
    private String instrumentKey;
    private String tradingSymbol;
    private String name;
    private String exchange;
    private LocalDateTime createdAt;

    // ---- Live quote overlay ----
    private Double ltp;
    private Double prevClose;
    private Double change;
    private Double changePercent;
    private LocalDateTime asOf;
}
