package com.djai.wealthadvisor.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient; // ✅ Modern Client

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketDataService {

    private final RestClient restClient; // ✅ Injected from AppConfig

    // --- API KEYS ---
    @Value("${api.finnhub.key}")
    private String finnhubKey;
    @Value("${api.finnhub.url}")
    private String finnhubUrl;

    @Value("${api.twelvedata.key}")
    private String twelveDataKey;
    @Value("${api.twelvedata.url}")
    private String twelveDataUrl;

    @Value("${api.alphavantage.key}")
    private String alphaKey;
    @Value("${api.alphavantage.url}")
    private String alphaUrl;

    @Value("${api.rapidapi.key}")
    private String rapidApiKey;
    @Value("${api.rapidapi.host}")
    private String rapidApiHost;

    @Data
    @AllArgsConstructor
    public static class StockQuote {
        private BigDecimal price;
        private BigDecimal changeAmount;
        private BigDecimal changePercent;
    }

    // Cache for 15 seconds
    @Cacheable(value = "stockQuotes", key = "#symbol")
    public StockQuote getRealTimeQuote(String symbol) {
        // 1. Handle Cash
        if ("USD".equalsIgnoreCase(symbol) || "CASH".equalsIgnoreCase(symbol) || "INR".equalsIgnoreCase(symbol)) {
            return new StockQuote(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        // 2. Normalize Symbol
        String indianSymbol = normalizeForIndia(symbol);
        
        StockQuote quote = null;

        // --- WATERFALL FAILOVER SYSTEM ---
        
        // Try 1: Finnhub
        try {
            quote = callFinnhub(indianSymbol);
        } catch (Exception e) {
            log.warn("Finnhub failed for {}: {}", indianSymbol, e.getMessage());
        }

        // Try 2: TwelveData
        if (quote == null) {
            try {
                quote = callTwelveData(symbol);
            } catch (Exception e) {
                log.warn("TwelveData failed for {}: {}", symbol, e.getMessage());
            }
        }

        // Try 3: Alpha Vantage
        if (quote == null) {
            try {
                quote = callAlphaVantage(indianSymbol);
            } catch (Exception e) {
                log.warn("Alpha Vantage failed for {}: {}", indianSymbol, e.getMessage());
            }
        }

        // Try 4: RapidAPI (Yahoo Finance)
        if (quote == null) {
            try {
                quote = callRapidApiYahoo(indianSymbol);
            } catch (Exception e) {
                log.warn("RapidAPI failed for {}: {}", indianSymbol, e.getMessage());
            }
        }

        // Fallback
        return (quote != null) ? quote : new StockQuote(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private String normalizeForIndia(String symbol) {
        symbol = symbol.toUpperCase().trim();
        if (symbol.contains(".")) return symbol;
        if (symbol.contains("-") || symbol.contains("USD")) return symbol;
        return symbol + ".NS";
    }

    // --- API IMPLEMENTATIONS (Updated to RestClient) ---

    private StockQuote callFinnhub(String symbol) {
        String url = String.format("%s/quote?symbol=%s&token=%s", finnhubUrl, symbol, finnhubKey);
        
        JsonNode root = restClient.get()
                .uri(url)
                .retrieve()
                .body(JsonNode.class);

        if (root != null && root.has("c") && !root.get("c").isNull()) {
            BigDecimal price = new BigDecimal(root.get("c").asText());
            if (price.compareTo(BigDecimal.ZERO) == 0) return null;
            
            return new StockQuote(
                price,
                new BigDecimal(root.get("d").asText()),
                new BigDecimal(root.get("dp").asText())
            );
        }
        return null;
    }

    private StockQuote callTwelveData(String symbol) {
        String rawSymbol = symbol.replace(".NS", "").replace(".BO", "");
        String exchange = symbol.contains(".BO") ? "BSE" : "NSE";

        // TwelveData prefers params
        String url = String.format("%s/quote?symbol=%s&exchange=%s&apikey=%s", twelveDataUrl, rawSymbol, exchange, twelveDataKey);
        
        JsonNode root = restClient.get()
                .uri(url)
                .retrieve()
                .body(JsonNode.class);

        if (root != null && root.has("close")) {
            return new StockQuote(
                new BigDecimal(root.get("close").asText()),
                new BigDecimal(root.get("change").asText()),
                new BigDecimal(root.get("percent_change").asText())
            );
        }
        return null;
    }

    private StockQuote callAlphaVantage(String symbol) {
        String url = String.format("%s?function=GLOBAL_QUOTE&symbol=%s&apikey=%s", alphaUrl, symbol, alphaKey);
        
        JsonNode root = restClient.get()
                .uri(url)
                .retrieve()
                .body(JsonNode.class);

        if (root != null && root.has("Global Quote")) {
            JsonNode q = root.get("Global Quote");
            if (q != null && q.has("05. price")) {
                String pct = q.get("10. change percent").asText().replace("%", "");
                return new StockQuote(
                    new BigDecimal(q.get("05. price").asText()),
                    new BigDecimal(q.get("09. change").asText()),
                    new BigDecimal(pct)
                );
            }
        }
        return null;
    }

    private StockQuote callRapidApiYahoo(String symbol) {
        // Yahoo Finance via RapidAPI
        // Note: rapidApiHost usually comes from config as 'apidojo-yahoo-finance-v1.p.rapidapi.com'
        // If 'rapidApiHost' is just the host string, we build the full URL carefully.
        
        // The previous code had: "https://%s/market/v2/get-quotes..."
        String url = String.format("https://%s/market/v2/get-quotes?region=IN&symbols=%s", rapidApiHost, symbol);
        
        JsonNode root = restClient.get()
                .uri(url)
                .header("X-RapidAPI-Key", rapidApiKey)
                .header("X-RapidAPI-Host", rapidApiHost)
                .retrieve()
                .body(JsonNode.class);
        
        if (root != null && root.has("quoteResponse")) {
            JsonNode result = root.get("quoteResponse").get("result");
            if (result.isArray() && result.size() > 0) {
                JsonNode q = result.get(0);
                return new StockQuote(
                    new BigDecimal(q.get("regularMarketPrice").asText()),
                    new BigDecimal(q.get("regularMarketChange").asText()),
                    new BigDecimal(q.get("regularMarketChangePercent").asText())
                );
            }
        }
        return null;
    }
}