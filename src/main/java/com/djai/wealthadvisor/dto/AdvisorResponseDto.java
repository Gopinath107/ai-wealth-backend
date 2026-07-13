package com.djai.wealthadvisor.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdvisorResponseDto {
    private Long sessionId;
    private String chatTitle;
    private String reply;
    private List<String> followUps;
    private List<String> instrumentKeys;        // raw keys for chart URL (backward compat)
    private List<InstrumentQuoteDto> instrumentQuotes; // rich metadata for UI card
    private LocalDateTime timestamp;
    private String status;

    /**
     * Per-instrument metadata returned alongside the chart.
     * All fields are populated from Upstox live quote + InstrumentMaster DB record.
     */
    @Data
    public static class InstrumentQuoteDto {
        private String instrumentKey;   // e.g. NSE_EQ|INE009A01021
        private String tradingSymbol;   // e.g. GOLDBEES
        private String name;            // e.g. Nippon India ETF Gold BeES
        private String exchange;        // NSE | BSE
        private double ltp;             // last traded price
        private double prevClose;
        private double change;
        private double changePercent;
        private String asOf;            // ISO-8601 with +05:30 offset (IST)
        private boolean delayed;        // true if asOf > 15 min ago
        private boolean marketClosed;   // true if outside 09:15–15:30 IST Mon–Fri
    }
}
