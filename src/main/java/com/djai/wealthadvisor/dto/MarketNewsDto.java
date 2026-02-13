package com.djai.wealthadvisor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarketNewsDto {

    // identity
    private String newsId;        // MarketAux uuid OR generated for Tavily
    private String title;
    private String description;
    private String url;

    // attribution
    private String source;        // domain
    private String provider;      // "marketaux" | "tavily"

    // media/time
    private String imageUrl;
    private String publishedAt;   // ISO string

    // basic sentiment (provider-based)
    private Double sentimentScore;
    private String sentimentLabel; // Bullish/Bearish/Neutral

    // AI impact
    private Boolean impactCached;
    private String aiImpactLabel;   // Bullish/Bearish/Neutral
    private Double aiImpactScore;   // -1..+1
    private String aiImpactSummary; // 1-2 lines
    private String aiKeyPoints;     // bullet string
    private String disclaimer;
}
