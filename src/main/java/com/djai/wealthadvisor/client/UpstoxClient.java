package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // ✅ Modern Client

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxClient {

    private final RestClient restClient; // ✅ Injected from AppConfig
    private final ObjectMapper objectMapper;

    @Value("${upstox.baseUrl}")
    private String baseUrl;

    @Value("${upstox.accessToken}")
    private String accessToken;

    public record QuoteSnapshot(double ltp, double prevClose) {}

    public Map<String, QuoteSnapshot> getLtpSnapshots(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return Map.of();

        // Try LTP endpoint first
        JsonNode root = callUpstox("/market-quote/ltp", instrumentKeys);

        // Fallback to full quotes if needed
        if (root == null || root.path("data").isMissingNode() || root.path("data").isNull()) {
            root = callUpstox("/market-quote/quotes", instrumentKeys);
        }

        if (root == null) return Map.of();

        JsonNode dataNode = root.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) return Map.of();

        Map<String, QuoteSnapshot> out = new HashMap<>();

        // Handle Object response: data: { "NSE_EQ|...": { ... } }
        if (dataNode.isObject()) {
            for (Iterator<String> it = dataNode.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                JsonNode q = dataNode.path(key);
                addToMap(out, key, q);
            }
            return out;
        }

        // Handle Array response (rare but possible in some versions)
        if (dataNode.isArray()) {
            for (JsonNode q : dataNode) {
                String key = q.path("instrument_key").asText(q.path("instrumentKey").asText(null));
                if (key != null && !key.isBlank()) {
                    addToMap(out, key, q);
                }
            }
        }

        return out;
    }

    private void addToMap(Map<String, QuoteSnapshot> out, String key, JsonNode q) {
        double ltp = firstDouble(q, "ltp", "last_price", "lastPrice", "last_traded_price");
        double prevClose = firstDouble(q, "cp", "close", "close_price", "previous_close", "prev_close");
        out.put(key, new QuoteSnapshot(ltp, prevClose));
    }

    private JsonNode callUpstox(String path, List<String> instrumentKeys) {
        try {
            String joined = String.join(",", instrumentKeys);

            // ✅ RestClient handles URI encoding and Headers cleanly
            String responseBody = restClient.get()
                    .uri(baseUrl + path + "?instrument_key={keys}", joined)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null) return null;
            return objectMapper.readTree(responseBody);

        } catch (Exception e) {
            log.warn("Upstox call failed {}: {}", path, e.getMessage());
            return null;
        }
    }

    private double firstDouble(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (!v.isMissingNode() && !v.isNull()) {
                if (v.isNumber()) return v.asDouble();
                if (v.isTextual()) {
                    try { return Double.parseDouble(v.asText()); } catch (Exception ignored) {}
                }
            }
        }
        return 0.0;
    }
}