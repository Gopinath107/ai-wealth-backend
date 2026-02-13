package com.djai.wealthadvisor.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ServerMarketEvent {
    private String type;          // candle | tick | market_info | error
    private String instrumentKey;

    private Double ltp;
    private String ltt;           // last traded time as string (Upstox sends timestamps as strings)

    private String interval;      // e.g. "I1", "1d"
    private String ts;            // candle timestamp
    private Double open;
    private Double high;
    private Double low;
    private Double close;
    private Double vol;

    private List<String> segmentsOpen; // optional: for market_info
    private String message;            // for error
}
