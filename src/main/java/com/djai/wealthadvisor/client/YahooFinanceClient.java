package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // ✅ Modern Client
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class YahooFinanceClient {

    private final RestClient restClient; // ✅ Changed from RestTemplate

    @Value("${api.rapidapi.key}")
    private String rapidApiKey;

    @Value("${api.rapidapi.host}")
    private String rapidApiHost;

    @Value("${api.rapidapi.url}")
    private String rapidApiBaseUrl;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMs = 2000;

    // ✅ Inject RestClient
    public YahooFinanceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public record YahooQuote(
            String symbol,
            String name,
            BigDecimal price,
            BigDecimal changePercent,
            Long marketCap,
            String exchange
    ) {}

    public record YahooSearchHit(String symbol, String name) {}

    // ✅ QUOTES
    public List<YahooQuote> getQuotes(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return List.of();

        String joined = String.join(",", symbols);
        CacheEntry c = cache.get(joined);
        long now = System.currentTimeMillis();
        if (c != null && (now - c.timeMs) < cacheTtlMs) return c.data;

        // Keep your encoding logic exactly as is
        URI uri = UriComponentsBuilder
                .fromHttpUrl(rapidApiBaseUrl)
                .path("/market/v2/get-quotes")
                .queryParam("region", "IN")
                .queryParam("symbols", joined)
                .build()
                .encode()   // ✅ Encodes M&M => M%26M
                .toUri();

        // ✅ Execute using RestClient
        JsonNode root = restClient.get()
                .uri(uri)
                .headers(h -> h.addAll(headers()))
                .retrieve()
                .body(JsonNode.class);

        if (root == null) return List.of();

        JsonNode result = root.path("quoteResponse").path("result");
        if (!result.isArray()) return List.of();

        List<YahooQuote> out = new ArrayList<>();
        for (JsonNode q : result) {
            String symbol = q.path("symbol").asText("");
            if (symbol.isBlank()) continue;

            String name = pickName(q);
            BigDecimal price = bd(q, "regularMarketPrice");
            BigDecimal changePct = bd(q, "regularMarketChangePercent");
            Long mcap = q.hasNonNull("marketCap") ? q.get("marketCap").asLong() : null;

            String exchange = detectExchange(symbol, q);

            out.add(new YahooQuote(symbol, name, price, changePct, mcap, exchange));
        }

        cache.put(joined, new CacheEntry(now, out));
        return out;
    }

    // ✅ SEARCH
    public List<YahooSearchHit> search(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();

        URI uri = UriComponentsBuilder
                .fromHttpUrl(rapidApiBaseUrl)
                .path("/auto-complete")
                .queryParam("q", query)
                .queryParam("region", "IN")
                .build()
                .encode()
                .toUri();

        // ✅ Execute using RestClient
        JsonNode root = restClient.get()
                .uri(uri)
                .headers(h -> h.addAll(headers()))
                .retrieve()
                .body(JsonNode.class);

        if (root == null) return List.of();

        JsonNode quotes = root.path("quotes");
        if (!quotes.isArray()) return List.of();

        List<YahooSearchHit> out = new ArrayList<>();
        for (JsonNode item : quotes) {
            String symbol = item.path("symbol").asText("");
            if (symbol.isBlank()) continue;

            if (!(symbol.endsWith(".NS") || symbol.endsWith(".BO"))) continue;

            String name = item.path("longname").asText(item.path("shortname").asText(symbol));
            out.add(new YahooSearchHit(symbol, name));

            if (out.size() >= limit) break;
        }
        return out;
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.set("X-RapidAPI-Key", rapidApiKey);
        h.set("X-RapidAPI-Host", rapidApiHost);
        return h;
    }

    private String pickName(JsonNode q) {
        String longName = q.path("longName").asText("");
        if (!longName.isBlank()) return longName;
        String shortName = q.path("shortName").asText("");
        if (!shortName.isBlank()) return shortName;
        return q.path("symbol").asText("");
    }

    private String detectExchange(String symbol, JsonNode q) {
        if (symbol.endsWith(".NS")) return "NSE";
        if (symbol.endsWith(".BO")) return "BSE";

        String full = q.path("fullExchangeName").asText("");
        if (full.toUpperCase().contains("NSE")) return "NSE";
        if (full.toUpperCase().contains("BSE")) return "BSE";
        return "UNKNOWN";
    }

    private BigDecimal bd(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return BigDecimal.ZERO;
        try { return new BigDecimal(v.asText("0")); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private record CacheEntry(long timeMs, List<YahooQuote> data) {}
    
    
}