package com.djai.wealthadvisor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

public class PortfolioDto {

    // --- EXISTING DTOs (Keep these) ---

    @Data
    public static class TradeRequest {
        private String symbol;
        @JsonProperty("action")
        private String action;
        private BigDecimal quantity;
        private BigDecimal price;
    }

    @Data
    public static class AssetResponse {
        private String id;
        private String symbol;
        private String name;
        private String type;
        private BigDecimal quantity;
        private BigDecimal price;
        private BigDecimal value;
        @JsonProperty("change24h")
        private BigDecimal change24h;
    }

    @Data
    public static class PortfolioSummary {
        private BigDecimal totalValue;
        private BigDecimal change24hValue;
        private BigDecimal change24hPercent;
        private List<AssetResponse> assets;
    }
    
    // --- NEW: Wrapper for AI Audit Request ---
    // Matches Payload: { "portfolio": { "totalValue": ... } }
    @Data
    public static class AuditRequest {
        private PortfolioSummary portfolio;
    }

    // --- OTHER EXISTING DTOs ---
    @Data
    public static class AddAssetRequest {
        private String symbol;
        private String name;
        private String type;
        private BigDecimal quantity;
        private BigDecimal price;
    }
    
    @Data
    public static class TransactionResponse {
        private String id; 
        private String date;
        private String type; 
        private String assetSymbol;
        private BigDecimal amount;
        private BigDecimal price;
        private BigDecimal total;
        private String status;
    }

    @Data
    public static class RebalancingSuggestion {
        private String symbol;
        private String action;
        private BigDecimal quantity;
        private String reason;
    }
}