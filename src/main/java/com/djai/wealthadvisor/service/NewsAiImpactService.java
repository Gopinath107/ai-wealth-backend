package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.MarketNewsDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NewsAiImpactService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // --- GROQ (FAST) ---
    @Value("${api.groq.key}")
    private String groqKey;

    @Value("${api.groq.url}")
    private String groqUrl;

    @Value("${api.groq.model}")
    private String groqModel;

    // --- OPENROUTER (FALLBACK) ---
    @Value("${api.openrouter.key}")
    private String openrouterKey;

    @Value("${api.openrouter.url}")
    private String openrouterUrl;

    @Value("${ai.model.primary}")
    private String openrouterModel;

    // keep low but not 0 to avoid ultra-conservative "Neutral" spam
    @Value("${ai.temperature:0.1}")
    private double temperature;

    // smaller output = faster
    @Value("${ai.maxTokens:700}")
    private int maxTokens;

    public record AiImpact(String url, String label, double score, String summary, List<String> keyPoints) {}

    public List<AiImpact> analyzeBatch(List<MarketNewsDto> batch) {
        if (batch == null || batch.isEmpty()) return List.of();

        // One call, not multiple (FASTER).
        // Keep prompt strict + force label-score alignment.
        String system = """
            You are a finance news impact analyst.
            Output STRICT JSON only (no markdown, no extra text).
            Score range: -1.0 (very bearish) to +1.0 (very bullish).
            label must be exactly one of: Bullish, Bearish, Neutral.
            RULE: label must match score:
              score >= 0.20 => Bullish
              score <= -0.20 => Bearish
              otherwise => Neutral
            Avoid returning Neutral for all items. Use Bullish/Bearish when the article likely affects:
              earnings, guidance, margins, rates, inflation, currency, oil, regulations, major deals, layoffs, demand.
            Summary max 2 lines. keyPoints exactly 3 short bullets.
            """;

        StringBuilder user = new StringBuilder();
        user.append("Return JSON exactly:\n");
        user.append("{\"items\":[{\"url\":\"...\",\"score\":0.0,\"label\":\"Bullish|Bearish|Neutral\",\"summary\":\"...\",\"keyPoints\":[\"...\",\"...\",\"...\"]}]}\n\n");

        for (int i = 0; i < batch.size(); i++) {
            MarketNewsDto n = batch.get(i);
            user.append("ITEM ").append(i + 1).append(":\n");
            user.append("url: ").append(n.getUrl()).append("\n");
            user.append("title: ").append(trimForAi(n.getTitle())).append("\n");
            user.append("desc: ").append(trimForAi(n.getDescription())).append("\n\n");
        }

        // 1) Groq first (fast)
        List<AiImpact> groq = callChatCompletions(groqUrl, groqKey, groqModel, system, user.toString(), 12);
        if (!groq.isEmpty()) return groq;

        // 2) OpenRouter fallback
        return callChatCompletions(openrouterUrl, openrouterKey, openrouterModel, system, user.toString(), 20);
    }

    private List<AiImpact> callChatCompletions(
            String url,
            String apiKey,
            String model,
            String system,
            String user,
            int timeoutSeconds
    ) {
        try {
            String payload = buildChatPayload(model, system, user);

            String resp = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (resp == null || resp.isBlank()) return List.of();

            String content = extractContentRobust(resp);
            if (content == null || content.isBlank()) return List.of();

            // Some providers still wrap/append text. Extract JSON safely.
            String jsonOnly = extractJsonObject(content);
            if (jsonOnly == null) return List.of();

            JsonNode root = objectMapper.readTree(jsonOnly);
            JsonNode items = root.path("items");
            if (!items.isArray()) return List.of();

            List<AiImpact> out = new ArrayList<>();
            for (JsonNode it : items) {
                String urlItem = it.path("url").asText(null);
                double score = it.path("score").asDouble(0.0);
                String label = it.path("label").asText(null);
                String summary = it.path("summary").asText(null);

                List<String> kp = new ArrayList<>();
                JsonNode kpn = it.path("keyPoints");
                if (kpn.isArray()) {
                    for (JsonNode k : kpn) {
                        String s = k.asText(null);
                        if (s != null && !s.isBlank()) kp.add(s.trim());
                    }
                }

                if (urlItem == null || summary == null) continue;

                double clamped = clampScore(score);
                String finalLabel = labelFromScoreAndText(clamped, label);

                out.add(new AiImpact(urlItem, finalLabel, clamped, summary.trim(), kp));
            }
            return out;

        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildChatPayload(String model, String system, String user) {
        return """
        {
          "model": "%s",
          "temperature": %s,
          "max_tokens": %d,
          "response_format": {"type":"json_object"},
          "messages": [
            {"role":"system","content": %s},
            {"role":"user","content": %s}
          ]
        }
        """.formatted(
                escape(model),
                temperature,
                maxTokens,
                jsonString(system),
                jsonString(user)
        );
    }

    private String extractContentRobust(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);

        JsonNode c1 = root.path("choices").path(0).path("message").path("content");
        if (!c1.isMissingNode() && !c1.isNull()) return c1.asText();

        // some providers return "text"
        JsonNode c2 = root.path("choices").path(0).path("text");
        if (!c2.isMissingNode() && !c2.isNull()) return c2.asText();

        return null;
    }

    private static String extractJsonObject(String s) {
        if (s == null) return null;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b < 0 || b <= a) return null;
        return s.substring(a, b + 1).trim();
    }

    private static String labelFromScoreAndText(double score, String labelRaw) {
        // primary rule: label must follow score
        if (score >= 0.20) return "Bullish";
        if (score <= -0.20) return "Bearish";

        // if score is neutral-ish, allow label text mapping if present
        String label = normalizeLabel(labelRaw);
        return label;
    }

    private static String normalizeLabel(String label) {
        if (label == null) return "Neutral";
        String x = label.trim().toLowerCase(Locale.ROOT);

        if (x.contains("bull") || x.contains("positive") || x.contains("upside") || x.contains("growth") || x.contains("optimistic")) {
            return "Bullish";
        }
        if (x.contains("bear") || x.contains("negative") || x.contains("downside") || x.contains("risk") || x.contains("recession") || x.contains("pessimistic")) {
            return "Bearish";
        }
        if (x.contains("hawkish")) return "Bearish"; // rates up = pressure
        if (x.contains("dovish")) return "Bullish";  // rates down = support

        return "Neutral";
    }

    private static double clampScore(double s) {
        if (s > 1.0) return 1.0;
        if (s < -1.0) return -1.0;
        return s;
    }

    private static String trimForAi(String s) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        // reduce prompt tokens => faster
        if (x.length() > 240) x = x.substring(0, 240) + "...";
        return x;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private static String jsonString(String s) {
        String x = (s == null) ? "" : s;
        x = x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + x + "\"";
    }
}
