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
    private String groqModel; // groq/compound — for final plan with web search

    @Value("${api.groq.model.lite}")
    private String groqModelLite; // llama-3.3-70b-versatile — for clarification questions

    private RestClient restClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    private static final int MAX_RETRIES = 3;

    // ════════════════════════════════════════════════════════════════
    // SYSTEM PROMPTS
    // ════════════════════════════════════════════════════════════════

    /** Lite model prompt — for clarification questions (no web search) */
    private String buildClarificationPrompt() {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        int currentYear = LocalDate.now(ZoneId.of("Asia/Kolkata")).getYear();
        int minTargetYear = currentYear + 1; // Must be at least next year
        return """
                You are an AI Financial Goal Planner for an Indian investment app.
                Today: %s. Current year: %d.

                CRITICAL RULES:
                1. Read the ENTIRE conversation history before responding.
                2. NEVER re-ask a question the user has ALREADY answered.
                3. Ask only ONE question at a time.
                4. Follow the exact order below. Skip steps already answered.

                STEP 1 — IDENTIFY THE GOAL (if not yet known):
                - PRODUCT (car, bike, laptop, phone): Ask brand/model. Suggest 4 Indian options.
                - REAL ESTATE (flat, house, plot): Ask city, then size.
                - INVESTMENT (retirement, wealth): Ask target amount.

                STEP 2 — COLLECT REMAINING DETAILS (one at a time, skip if already given):
                a) Target year (MUST be >= %d). Suggest: ["%d", "%d", "%d"]
                b) Monthly contribution. Suggest: ["₹5,000", "₹10,000", "₹15,000", "₹25,000"]
                c) Risk preference. Suggest: ["Low", "Moderate", "High"]

                STEP 3 — FEASIBILITY CHECK (only after ALL 3 details above are collected):
                If monthlyContribution × months remaining < 30%% of expected cost → warn user, suggest adjustments.

                OUTPUT FORMAT (strict JSON only, absolutely no markdown or text outside JSON):

                If still collecting info:
                {"question":"your next question","suggestions":["A","B","C","D"]}

                If ALL details collected AND feasible:
                {"ready":true,"summary":"Buy [item], target year [YYYY], monthly ₹[amount], risk [level]"}

                NEVER output ready until you have ALL: goal + target year + monthly contribution + risk.
                """
                .formatted(today, currentYear, minTargetYear, minTargetYear, minTargetYear + 1, minTargetYear + 2);
    }

    /**
     * Compound model prompt — for final plan generation with real-time web search
     * pricing
     */
    private String buildPlanPrompt() {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        return """
                Today: %s. Search for the CURRENT INDIA on-road/market price. Use ONLY ₹ (INR). NEVER use $.
                Generate a financial plan as strict JSON:
                {"name":"string","type":"Purchase|Retirement|Savings|Emergency Fund|Education|Vacation|House Down Payment|Wedding|Debt Payoff|Custom","targetAmount":number,"currentAmount":0,"deadline":"YYYY-MM-DD","monthlyContribution":number,"riskProfile":"string","allocationStrategy":[{"assetClass":"string","percentage":number}],"milestones":["string with ₹ amounts"]}
                Allocation: <3yrs: FD, Liquid Funds. 3-7yrs: Hybrid, Debt, Gold. >7yrs: Equity MF, Stocks, PPF.
                All deadlines MUST be after today.
                """
                .formatted(today);
    }

    // ════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════════
    @Override
    public GoalDto generatePlan(String userPrompt) {
        return generatePlanFromMessages(List.of(Map.of("role", "user", "content", userPrompt)), null);
    }

    @Override
    public GoalDto generatePlanFromMessages(List<Map<String, String>> conversationHistory, Long userId) {
        log.info("Generating AI plan for userId={}, {} messages in history", userId, conversationHistory.size());
        try {
            // PHASE 1: Use lite model for clarification
            String liteResponse = callLiteModel(conversationHistory);
            String cleanLite = cleanResponse(liteResponse);
            log.info("Lite model response: {}", cleanLite);

            JsonNode liteJson = objectMapper.readTree(cleanLite);

            // Check if lite model says "all details collected"
            if (liteJson.has("ready") && liteJson.path("ready").asBoolean(false)) {
                // PHASE 2: Use compound model for real-time pricing
                String summary = liteJson.path("summary").asText("Generate a financial plan");
                log.info("All details collected. Calling compound model with summary: {}", summary);

                GoalDto result = callCompoundModel(summary);
                result.setUserId(userId);
                return result;
            }

            // Still clarifying — return the question
            GoalDto result = objectMapper.readValue(cleanLite, GoalDto.class);
            result.setUserId(userId);
            return result;

        } catch (Exception e) {
            log.error("AI Goal Generation Failed", e);
            throw new RuntimeException("Failed to generate goal plan: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PHASE 1: Lite model — clarification questions
    // ════════════════════════════════════════════════════════════════
    private String callLiteModel(List<Map<String, String>> conversationHistory) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildClarificationPrompt()));
        messages.addAll(conversationHistory);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModelLite);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 512);

        return callGroqApi(payload);
    }

    // ════════════════════════════════════════════════════════════════
    // PHASE 2: Compound model — plan with real-time pricing
    // ════════════════════════════════════════════════════════════════
    private GoalDto callCompoundModel(String summary) {
        // Send ONLY the summary + tiny plan prompt to compound model
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildPlanPrompt()));
        messages.add(Map.of("role", "user", "content", summary));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1024);

        try {
            String rawJson = callGroqApi(payload);
            String cleanJson = cleanResponse(rawJson);
            log.info("Compound model plan JSON: {}", cleanJson);
            return objectMapper.readValue(cleanJson, GoalDto.class);
        } catch (Exception e) {
            // FALLBACK: If compound model fails (413/429), generate plan with lite model
            log.warn("Compound model failed ({}), falling back to lite model for plan generation", e.getMessage());
            return callLiteModelForPlan(summary);
        }
    }

    /** Fallback: generate plan using lite model if compound model is unavailable */
    private GoalDto callLiteModelForPlan(String summary) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildPlanPrompt()));
        messages.add(Map.of("role", "user", "content",
                summary + " Use current approximate India market prices in ₹."));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModelLite);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1024);

        try {
            String rawJson = callGroqApi(payload);
            String cleanJson = cleanResponse(rawJson);
            log.info("Lite model fallback plan JSON: {}", cleanJson);
            return objectMapper.readValue(cleanJson, GoalDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Both compound and lite models failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // HTTP CLIENT — with retry on 429
    // ════════════════════════════════════════════════════════════════
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

    private long parseRetryDelay(String errorBody) {
        try {
            if (errorBody != null) {
                java.util.regex.Matcher mMs = java.util.regex.Pattern.compile("in (\\d+)ms").matcher(errorBody);
                if (mMs.find()) {
                    return Long.parseLong(mMs.group(1)) + 200;
                }
                java.util.regex.Matcher mS = java.util.regex.Pattern.compile("in ([\\d.]+)s").matcher(errorBody);
                if (mS.find()) {
                    return (long) (Double.parseDouble(mS.group(1)) * 1000) + 200;
                }
            }
        } catch (Exception ignored) {
        }
        return 2000;
    }

    // ════════════════════════════════════════════════════════════════
    // RESPONSE CLEANING
    // ════════════════════════════════════════════════════════════════
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

        // FALLBACK: AI returned plain text instead of JSON — wrap as a question
        log.warn("AI returned non-JSON response, wrapping as question: {}", clean);
        String escaped = clean.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "");
        return "{\"question\":\"" + escaped + "\",\"suggestions\":[]}";
    }
}
