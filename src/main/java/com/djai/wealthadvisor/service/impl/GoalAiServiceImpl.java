package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.service.GoalAiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GoalAiServiceImpl implements GoalAiService {

    private final ObjectMapper objectMapper;

    @Value("${ai.http.connectTimeoutMs:10000}")
    private int connectTimeoutMs;

    @Value("${ai.http.readTimeoutMs:90000}")
    private int readTimeoutMs;

    @Value("${api.groq.key}")
    private String groqApiKey;

    @Value("${api.groq.url}")
    private String groqUrl;

    @Value("${api.groq.model}")
    private String groqModel;

    private RestClient restClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    // ════════════════════════════════════════════════════════════════
    // SYSTEM PROMPT — Built dynamically with today's date
    // ════════════════════════════════════════════════════════════════
    private String buildSystemPrompt() {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        return """
                You are an AI Financial Goal Planner for an Indian investment app.
                Today's date: %s. All deadlines MUST be after today. NEVER suggest any past date.

                RULES:
                1. PRODUCT purchase (car, bike, laptop, phone) without brand/model specified:
                   → Ask which brand/model. Provide 4 Indian-market suggestions. Do NOT plan until answered.
                2. REAL ESTATE (flat, house, land, plot, villa):
                   → If no city: ask user to pick from Chennai, Bangalore, Hyderabad, Mumbai, Delhi, Kolkata, Pune, Ahmedabad.
                   → Land/plot: ask square feet. Flat/home: ask BHK. Proceed only after city + size collected.
                3. INVESTMENT GOAL (retirement, wealth, passive income):
                   → Ask: target amount, target year, monthly contribution, risk preference (Low/Moderate/High).
                4. Never assume or fabricate prices. Ask step-by-step, one question at a time.
                5. When ready to plan: search for CURRENT INDIA on-road/market price. Use ONLY Indian pricing sources. All amounts in ₹ (INR). NEVER use $.

                OUTPUT (strict JSON, no markdown):

                If clarifying:
                {"question":"your question","suggestions":["Option 1","Option 2","Option 3","Option 4"]}

                If planning (all details collected):
                {"name":"string","type":"Purchase|Retirement|Savings|Emergency Fund|Education|Vacation|House Down Payment|Wedding|Debt Payoff|Custom","targetAmount":number,"currentAmount":0,"deadline":"YYYY-MM-DD","monthlyContribution":number,"riskProfile":"string","allocationStrategy":[{"assetClass":"string","percentage":number}],"milestones":["string with ₹ amounts"]}

                Allocation: <3yrs: FD, Liquid Funds. 3-7yrs: Hybrid, Debt, Gold. >7yrs: Equity MF, Stocks, PPF.
                """
                .formatted(today);
    }

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API — accepts full conversation history
    // ════════════════════════════════════════════════════════════════
    @Override
    public GoalDto generatePlan(String userPrompt) {
        return generatePlanFromMessages(List.of(Map.of("role", "user", "content", userPrompt)), null);
    }

    @Override
    public GoalDto generatePlanFromMessages(List<Map<String, String>> conversationHistory, Long userId) {
        log.info("Generating AI plan for userId={}, {} messages in history", userId, conversationHistory.size());
        try {
            Map<String, Object> payload = preparePayload(conversationHistory);
            String rawJson = callGroqApi(payload);
            String cleanJson = cleanResponse(rawJson);
            log.info("Clean AI JSON: {}", cleanJson);
            GoalDto result = objectMapper.readValue(cleanJson, GoalDto.class);
            result.setUserId(userId); // Always attach userId to response
            return result;
        } catch (Exception e) {
            log.error("AI Goal Generation Failed", e);
            throw new RuntimeException("Failed to generate goal plan: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INTERNALS
    // ════════════════════════════════════════════════════════════════
    private static final int MAX_HISTORY_MESSAGES = 6; // keep token usage low for compound model
    private static final int MAX_RETRIES = 3;

    private Map<String, Object> preparePayload(List<Map<String, String>> conversationHistory) {
        List<Map<String, String>> trimmed = trimConversation(conversationHistory);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.addAll(trimmed);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1024);
        return payload;
    }

    /**
     * Trim conversation to reduce token usage.
     * Keeps the first user message (original goal) + the last N messages for
     * context.
     */
    private List<Map<String, String>> trimConversation(List<Map<String, String>> history) {
        if (history.size() <= MAX_HISTORY_MESSAGES) {
            return history;
        }
        List<Map<String, String>> trimmed = new ArrayList<>();
        trimmed.add(history.get(0)); // always keep the first user message (the goal)
        // keep the last (MAX_HISTORY_MESSAGES - 1) messages
        int start = history.size() - (MAX_HISTORY_MESSAGES - 1);
        trimmed.addAll(history.subList(start, history.size()));
        log.info("Trimmed conversation from {} to {} messages", history.size(), trimmed.size());
        return trimmed;
    }

    private String callGroqApi(Map<String, Object> payload) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String body = restClient.post().uri(groqUrl)
                        .header("Authorization", "Bearer " + groqApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(body);
                return root.path("choices").get(0).path("message").path("content").asText();
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                // 429 — parse retry delay from Groq error and wait
                long waitMs = parseRetryDelay(e.getResponseBodyAsString());
                log.warn("Rate limited (attempt {}/{}). Waiting {}ms before retry...", attempt, MAX_RETRIES, waitMs);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Rate limit exceeded after " + MAX_RETRIES
                            + " retries. Please try again in a few seconds.");
                }
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for rate limit retry");
                }
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                throw new RuntimeException(e.getStatusCode() + " " + e.getResponseBodyAsString());
            } catch (Exception e) {
                throw new RuntimeException("Invalid response from AI provider: " + e.getMessage());
            }
        }
        throw new RuntimeException("Failed after " + MAX_RETRIES + " retries");
    }

    /**
     * Parse retry delay from Groq 429 error body. Looks for "try again in XXms" or
     * "try again in X.Xs".
     * Falls back to 2 seconds if parsing fails.
     */
    private long parseRetryDelay(String errorBody) {
        try {
            if (errorBody != null) {
                // Match patterns like "in 852ms" or "in 1.5s"
                java.util.regex.Matcher mMs = java.util.regex.Pattern.compile("in (\\d+)ms").matcher(errorBody);
                if (mMs.find()) {
                    return Long.parseLong(mMs.group(1)) + 200; // add 200ms buffer
                }
                java.util.regex.Matcher mS = java.util.regex.Pattern.compile("in ([\\d.]+)s").matcher(errorBody);
                if (mS.find()) {
                    return (long) (Double.parseDouble(mS.group(1)) * 1000) + 200;
                }
            }
        } catch (Exception ignored) {
        }
        return 2000; // default 2s wait
    }

    private String cleanResponse(String text) {
        if (text == null || text.isBlank())
            return "{\"question\":\"I couldn't generate a response. Please try again.\",\"suggestions\":[]}";
        String clean = text.replace("```json", "").replace("```", "").trim();

        // Handle double-serialized JSON
        if (clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length() - 1).replace("\\\"", "\"");
        }

        int firstBrace = clean.indexOf("{");
        int lastBrace = clean.lastIndexOf("}");
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return clean.substring(firstBrace, lastBrace + 1);
        }

        // FALLBACK: AI returned plain text instead of JSON — wrap it as a clarification
        // question
        log.warn("AI returned non-JSON response, wrapping as question: {}", clean);
        String escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
        return "{\"question\":\"" + escaped + "\",\"suggestions\":[]}";
    }
}
