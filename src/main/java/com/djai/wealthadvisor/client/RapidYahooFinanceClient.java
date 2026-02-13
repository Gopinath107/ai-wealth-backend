package com.djai.wealthadvisor.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient; // ✅ Modern Client
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RapidYahooFinanceClient {

    private final RestClient restClient; // ✅ Changed from WebClient
    private final ObjectMapper objectMapper;

    @Value("${api.rapidapi.url}")
    private String baseUrl;

    @Value("${api.rapidapi.key}")
    private String apiKey;

    @Value("${api.rapidapi.host}")
    private String apiHost;

    public Map<String, QuoteMini> getQuotesMarketCap(List<String> yahooSymbols, String region) {
        if (yahooSymbols == null || yahooSymbols.isEmpty()) return Map.of();

        String symbolsCsv = String.join(",", yahooSymbols);

        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/market/v2/get-quotes")
                .queryParam("region", (region == null || region.isBlank()) ? "IN" : region)
                .queryParam("symbols", symbolsCsv)
                .build()
                .encode()
                .toUriString();

        try {
            // ✅ RestClient (Synchronous, uses System DNS)
            String body = restClient.get()
                    .uri(url)
                    .header("X-RapidAPI-Key", apiKey)
                    .header("X-RapidAPI-Host", apiHost)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) return Map.of();

            JsonNode root = objectMapper.readTree(body);
            JsonNode result = root.path("quoteResponse").path("result");
            if (!result.isArray()) return Map.of();

            Map<String, QuoteMini> out = new HashMap<>();
            for (JsonNode n : result) {
                String symbol = n.path("symbol").asText(null);
                if (symbol == null) continue;

                long marketCap = n.path("marketCap").asLong(0L);
                String currency = n.path("currency").asText(null);

                out.put(symbol, new QuoteMini(marketCap == 0 ? null : marketCap, currency));
            }
            return out;

        } catch (Exception e) {
            throw new RuntimeException("Yahoo RapidAPI get-quotes failed: " + e.getMessage(), e);
        }
    }

    public record QuoteMini(Long marketCap, String currency) {}
}