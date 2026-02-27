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
    private Map<String, Object> preparePayload(List<Map<String, String>> conversationHistory) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt()));
        messages.addAll(conversationHistory);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1024);
        return payload;
    }

    private String callGroqApi(Map<String, Object> payload) {
        String body = restClient.post().uri(groqUrl)
                .header("Authorization", "Bearer " + groqApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException("Invalid response from AI provider");
        }
    }

    private String cleanResponse(String text) {
        if (text == null)
            return "{}";
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
        return clean;
    }
}
