package com.djai.wealthadvisor.client;

import com.djai.wealthadvisor.dto.CandleDto;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // ✅ Modern Client

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UpstoxHistoryClient {

    private final RestClient restClient; // ✅ Injected

    @Value("${upstox.baseHost}")
    private String baseHost;

    @Value("${upstox.accessToken}")
    private String accessToken;

    public List<CandleDto> getIntradayCandles(String instrumentKey, String unit, int interval) {
        return fetchCandles("/v3/historical-candle/intraday/{instrumentKey}/{unit}/{interval}",
                instrumentKey, unit, String.valueOf(interval));
    }

    public List<CandleDto> getHistoricalCandles(String instrumentKey, String unit, int interval, String toDate, String fromDate) {
        return fetchCandles("/v3/historical-candle/{instrumentKey}/{unit}/{interval}/{toDate}/{fromDate}",
                instrumentKey, unit, String.valueOf(interval), toDate, fromDate);
    }

    private List<CandleDto> fetchCandles(String pathTemplate, Object... uriVars) {
        try {
            JsonNode root = restClient.get()
                    .uri(baseHost + pathTemplate, uriVars) // ✅ Auto-encodes "|" to "%7C"
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) return List.of();

            JsonNode candlesNode = root.path("data").path("candles");
            if (!candlesNode.isArray()) return List.of();

            List<CandleDto> out = new ArrayList<>();
            for (JsonNode row : candlesNode) {
                if (!row.isArray() || row.size() < 6) continue;
                
                // Format: [timestamp, open, high, low, close, volume, oi]
                out.add(new CandleDto(
                        row.get(0).asText(),
                        row.get(1).asDouble(0),
                        row.get(2).asDouble(0),
                        row.get(3).asDouble(0),
                        row.get(4).asDouble(0),
                        row.get(5).asLong(0),
                        row.size() >= 7 ? row.get(6).asLong(0) : 0
                ));
            }
            return out;

        } catch (Exception e) {
            log.warn("Upstox candles failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}