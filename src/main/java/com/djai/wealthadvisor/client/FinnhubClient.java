package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Safe wrapper for Finnhub analyst endpoints.
 * Always Optional.empty() on failure => your API never breaks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinnhubClient {

    private final RestClient restClient;

    @Value("${api.finnhub.url}")
    private String baseUrl;

    @Value("${api.finnhub.key}")
    private String apiKey;

    public Optional<PriceTarget> getPriceTarget(String symbol) {
        try {
            JsonNode root = restClient.get()
                    .uri(baseUrl + "/stock/price-target?symbol={symbol}&token={token}", symbol, apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) return Optional.empty();

            int analysts = root.path("numberAnalysts").asInt(0);
            double mean = root.path("targetMean").asDouble(0);
            if (analysts <= 0 || mean <= 0) return Optional.empty();

            return Optional.of(new PriceTarget(
                    analysts,
                    mean,
                    root.path("targetHigh").asDouble(0),
                    root.path("targetLow").asDouble(0)
            ));
        } catch (Exception e) {
            log.debug("Finnhub price-target failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<RecommendationTrend> getRecommendationTrend(String symbol) {
        try {
            JsonNode root = restClient.get()
                    .uri(baseUrl + "/stock/recommendation?symbol={symbol}&token={token}", symbol, apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.isArray() || root.isEmpty()) return Optional.empty();

            JsonNode latest = root.get(0);
            return Optional.of(new RecommendationTrend(
                    latest.path("period").asText(null),
                    latest.path("strongBuy").asInt(0),
                    latest.path("buy").asInt(0),
                    latest.path("hold").asInt(0),
                    latest.path("sell").asInt(0),
                    latest.path("strongSell").asInt(0)
            ));
        } catch (Exception e) {
            log.debug("Finnhub recommendation failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public record PriceTarget(int numberAnalysts, double targetMean, double targetHigh, double targetLow) {}
    public record RecommendationTrend(String period, int strongBuy, int buy, int hold, int sell, int strongSell) {}
}
