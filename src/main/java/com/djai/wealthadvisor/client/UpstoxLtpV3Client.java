package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // <--- NEW IMPORT

import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
public class UpstoxLtpV3Client {

    private final ObjectMapper objectMapper;
    private final RestClient restClient; // <--- Changed from WebClient

    @Value("${upstox.accessToken}")
    private String accessToken;

    @Value("${upstox.v3.baseUrl}")
    private String v3BaseUrl;

    public Map<String, LtpSnapshot> getLtpByInstrumentKey(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty())
            return Collections.emptyMap();

        String joined = String.join(",", instrumentKeys);

        try {
            // "RestClient" is synchronous by default. No .block() needed.
            // It uses the system's native DNS resolver, fixing the "Netty" error.
            String body = restClient.get()
                    .uri(v3BaseUrl + "/market-quote/ltp?instrument_key={keys}", joined)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class); // <--- Direct body fetch

            if (body == null || body.isBlank())
                return Collections.emptyMap();

            return parseLtpResponse(body);

        } catch (Exception ex) {
            // In interviews, always wrap external errors in a custom exception or log
            // clearly
            throw new RuntimeException("Upstox LTP V3 failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, LtpSnapshot> parseLtpResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode dataNode = root.path("data");
        if (!dataNode.isObject())
            return Collections.emptyMap();

        Map<String, LtpSnapshot> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = dataNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode n = e.getValue();

            String instrumentToken = n.path("instrument_token").asText("");
            if (instrumentToken.isBlank())
                instrumentToken = n.path("instrument_key").asText("");
            if (instrumentToken.isBlank())
                continue;

            double lastPrice = n.path("last_price").asDouble(0.0);
            double closePrice = n.path("cp").asDouble(0.0);
            double change = (closePrice > 0) ? (lastPrice - closePrice) : 0.0;
            double changePct = (closePrice > 0) ? (change * 100.0 / closePrice) : 0.0;

            LtpSnapshot snap = new LtpSnapshot(
                    instrumentToken, lastPrice, closePrice, change, changePct, LocalDateTime.now());
            out.put(instrumentToken, snap);
        }
        return out;
    }

    @Data
    @AllArgsConstructor
    public static class LtpSnapshot {
        private String instrumentKey;
        private double lastPrice;
        private double closePrice;
        private double change;
        private double changePercent;
        private LocalDateTime asOf;
    }
}