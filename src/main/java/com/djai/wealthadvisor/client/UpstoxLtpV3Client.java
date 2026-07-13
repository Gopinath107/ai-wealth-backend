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
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Fetches LTP (last traded price) snapshots from the Upstox v3 Market-Quote API.
 *
 * FIX (Issue 5): The previous version indexed the output map only by
 * instrument_token read from the JSON body, which may use a URL-encoded form
 * (e.g. "NSE_EQ%7CINE...") while callers look up by the original pipe form
 * ("NSE_EQ|INE...").  We now also index every entry under BOTH the JSON key
 * and the canonicalised pipe form so snaps.get(key) always succeeds.
 *
 * FIX (Issue 4): asOf is now set from the "ts" / "last_traded_time" field in
 * the Upstox response when present, and always expressed in IST
 * (Asia/Kolkata).  Fallback is ZonedDateTime.now(IST).
 */
@Component
@RequiredArgsConstructor
public class UpstoxLtpV3Client {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TS_PARSER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Value("${upstox.accessToken}")
    private String accessToken;

    @Value("${upstox.v3.baseUrl}")
    private String v3BaseUrl;

    public Map<String, LtpSnapshot> getLtpByInstrumentKey(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty())
            return Collections.emptyMap();

        String joined = String.join(",", instrumentKeys);

        try {
            String body = restClient.get()
                    .uri(v3BaseUrl + "/market-quote/ltp?instrument_key={keys}", joined)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank())
                return Collections.emptyMap();

            return parseLtpResponse(body, instrumentKeys);

        } catch (Exception ex) {
            throw new RuntimeException("Upstox LTP V3 failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses the Upstox LTP response and returns a map keyed by the
     * canonicalised instrument key (e.g. "NSE_EQ|INE009A01021").
     * <p>
     * The response data node uses the Upstox-internal key which may be
     * URL-percent-encoded.  We normalise it back to pipe form and also
     * cross-index by the original input keys so look-ups always succeed.
     */
    private Map<String, LtpSnapshot> parseLtpResponse(String body,
                                                       List<String> requestedKeys) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode dataNode = root.path("data");
        if (!dataNode.isObject())
            return Collections.emptyMap();

        Map<String, LtpSnapshot> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = dataNode.fields();

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode n = e.getValue();

            // ── Resolve instrument key ───────────────────────────────────────
            // Upstox may return "instrument_token" or "instrument_key"; prefer
            // instrument_token as it matches what we sent.
            String rawToken = n.path("instrument_token").asText("");
            if (rawToken.isBlank()) rawToken = n.path("instrument_key").asText("");
            if (rawToken.isBlank()) rawToken = e.getKey(); // last resort: use map key

            // Upstox sometimes percent-encodes the pipe:  NSE_EQ%7CINE... → NSE_EQ|INE...
            String canonKey = java.net.URLDecoder.decode(rawToken,
                    java.nio.charset.StandardCharsets.UTF_8);

            // ── Prices ───────────────────────────────────────────────────────
            double lastPrice   = n.path("last_price").asDouble(0.0);
            double closePrice  = n.path("cp").asDouble(0.0);        // previous close
            double change      = (closePrice > 0) ? (lastPrice - closePrice) : 0.0;
            double changePct   = (closePrice > 0) ? (change * 100.0 / closePrice) : 0.0;

            // ── Timestamp (FIX Issue 4) ──────────────────────────────────────
            // Try to read actual quote timestamp from Upstox response fields.
            // Field candidates: "ts", "last_traded_time", "exchange_time"
            ZonedDateTime asOf = parseUpstoxTimestamp(n);

            LtpSnapshot snap = new LtpSnapshot(canonKey, lastPrice, closePrice,
                    change, changePct, asOf.toLocalDateTime());

            // Index under the canonical (decoded) form
            out.put(canonKey, snap);

            // Also index under the raw (possibly encoded) form to be safe
            if (!rawToken.equals(canonKey)) {
                out.put(rawToken, snap);
            }
        }

        // ── Cross-index by every requested input key (FIX Issue 5 core) ────
        // Some Upstox responses use a slightly different separator or casing.
        // Walk the input keys and try to find a match ignoring case/encoding.
        for (String req : requestedKeys) {
            if (!out.containsKey(req)) {
                // Try case-insensitive search among existing keys
                out.entrySet().stream()
                        .filter(en -> en.getKey().equalsIgnoreCase(req)
                                || en.getKey().equalsIgnoreCase(
                                java.net.URLEncoder.encode(req,
                                        java.nio.charset.StandardCharsets.UTF_8)
                                        .replace("%7C", "|")))
                        .findFirst()
                        .ifPresent(en -> out.put(req, en.getValue()));
            }
        }

        return out;
    }

    /**
     * Extracts the quote timestamp from known Upstox v3 field names.
     * Falls back to now() in IST if none are present.
     */
    private ZonedDateTime parseUpstoxTimestamp(JsonNode n) {
        // Try common Upstox field names for quote timestamp
        for (String field : new String[]{"ts", "last_traded_time", "exchange_time",
                "feed_timestamp", "timestamp"}) {
            String raw = n.path(field).asText("");
            if (!raw.isBlank()) {
                try {
                    // Try epoch millis first
                    long millis = Long.parseLong(raw);
                    return ZonedDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(millis), IST);
                } catch (NumberFormatException ignored) {}
                try {
                    // Try "yyyy-MM-dd HH:mm:ss" (Upstox common format)
                    LocalDateTime ldt = LocalDateTime.parse(raw, TS_PARSER);
                    return ldt.atZone(IST);
                } catch (Exception ignored) {}
                try {
                    // Try ISO-8601
                    return ZonedDateTime.parse(raw);
                } catch (Exception ignored) {}
            }
        }
        // Fallback: current time in IST
        return ZonedDateTime.now(IST);
    }

    @Data
    @AllArgsConstructor
    public static class LtpSnapshot {
        private String instrumentKey;
        private double lastPrice;
        private double closePrice;
        private double change;
        private double changePercent;
        private LocalDateTime asOf; // Always in IST
    }
}