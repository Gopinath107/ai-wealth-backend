package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // ✅ Modern Client

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Component
@RequiredArgsConstructor
public class UpstoxInstrumentFileClient {

    private final RestClient restClient; // ✅ Injected
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper; // Inject existing mapper

    @Value("${upstox.instruments.url}")
    private String instrumentsUrl;

    public int downloadAndUpsertEqAndIndex() {
        // ✅ Streaming large file with RestClient
        return restClient.get()
                .uri(instrumentsUrl)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new RuntimeException("Failed to download instruments: " + response.getStatusCode());
                    }
                    try (InputStream raw = response.getBody();
                         InputStream gz = new GZIPInputStream(raw)) {
                        return parseAndUpsert(gz);
                    } catch (Exception e) {
                        throw new RuntimeException("Parse failed", e);
                    }
                });
    }

    private int parseAndUpsert(InputStream jsonStream) throws Exception {
        JsonFactory factory = mapper.getFactory();
        int totalUpserted = 0;

        String sql = """
            INSERT INTO dj.instrument_master
              (instrument_key, trading_symbol, name, exchange, isin, segment, instrument_type, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (instrument_key) DO UPDATE SET
              trading_symbol = EXCLUDED.trading_symbol,
              name = EXCLUDED.name,
              exchange = EXCLUDED.exchange,
              isin = EXCLUDED.isin,
              segment = EXCLUDED.segment,
              instrument_type = EXCLUDED.instrument_type,
              updated_at = EXCLUDED.updated_at
            """;

        try (JsonParser p = factory.createParser(jsonStream)) {
            if (p.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Instrument file is not a JSON array");
            }

            List<Object[]> batch = new ArrayList<>(1000);
            LocalDateTime now = LocalDateTime.now();

            while (p.nextToken() != JsonToken.END_ARRAY) {
                JsonNode node = mapper.readTree(p);

                String segment = text(node, "segment");
                String instrumentType = text(node, "instrument_type");

                if (!isWanted(segment, instrumentType)) continue;

                String instrumentKey = text(node, "instrument_key");
                String tradingSymbol = text(node, "trading_symbol");
                if (instrumentKey.isBlank() || tradingSymbol.isBlank()) continue;

                batch.add(new Object[]{
                        instrumentKey, tradingSymbol, text(node, "name"), text(node, "exchange"),
                        text(node, "isin"), segment, instrumentType, now
                });

                if (batch.size() >= 1000) {
                    totalUpserted += jdbcTemplate.batchUpdate(sql, batch).length;
                    batch.clear();
                }
            }

            if (!batch.isEmpty()) {
                totalUpserted += jdbcTemplate.batchUpdate(sql, batch).length;
            }
        }
        return totalUpserted;
    }

    private boolean isWanted(String segment, String instrumentType) {
        if (segment == null) return false;
        return switch (segment) {
            case "NSE_EQ", "BSE_EQ" -> "EQ".equalsIgnoreCase(instrumentType);
            case "NSE_INDEX", "BSE_INDEX" -> "INDEX".equalsIgnoreCase(instrumentType);
            default -> false;
        };
    }

    private String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? "" : v.asText("");
    }
}