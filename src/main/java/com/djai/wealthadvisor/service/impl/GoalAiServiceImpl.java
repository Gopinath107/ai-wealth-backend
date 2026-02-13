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
    // SYSTEM PROMPT — Multi-turn chatbot for goal planning
    // ════════════════════════════════════════════════════════════════
    private static final String SYSTEM_PROMPT = """
            You are an AI Financial Goal Planning Assistant inside a goal-based investment planning application.

            Your job is to create structured financial plans. However, before generating a plan, you must ensure all required goal details are collected from the user.

            Follow these strict rules:

            1. If the user mentions buying a PRODUCT (car, bike, laptop, phone, etc.) but does not specify the exact brand or model:
               → Ask a clarification question.
               → Provide 4 relevant suggestions for the user to pick from.
               Do NOT generate a financial plan until this is answered.

            2. If the user mentions buying REAL ESTATE (flat, house, land, plot, villa):
               → If city/region is not mentioned:
                    Ask the user to select a metro city only from this list:
                    Chennai, Bangalore, Hyderabad, Mumbai, Delhi, Kolkata, Pune, Ahmedabad
               → If land/plot:
                    Ask: "How many grounds or square feet are you planning to buy?"
               → If flat/home:
                    Ask: "How many BHK are you targeting?"
               → Only proceed after metro city + size details are collected.

            3. If the user mentions an INVESTMENT GOAL (future wealth, retirement, passive income, etc.):
               → Ask:
                    - Target amount?
                    - Target year?
                    - Monthly contribution capacity?
                    - Risk preference (Low / Moderate / High)?

            4. If any required information is missing:
               → Always ask follow-up clarification questions first.
               → Never assume price.
               → Never fabricate numbers.
               → Ask step-by-step. Do not overwhelm the user with too many questions at once.

            5. Once ALL required details are collected:
               → Estimate target amount based on current Indian market prices in INR.
               → Use ₹ symbol everywhere. NEVER use $.
               → Generate a realistic plan.

            # OUTPUT FORMAT (Strict JSON only, no markdown):

            If CLARIFYING, output:
            {
              "question": "Your follow-up question here",
              "suggestions": ["Option 1", "Option 2", "Option 3", "Option 4"]
            }

            If GENERATING A PLAN (all details collected), output:
            {
              "name": "string",
              "type": "string (Purchase/Retirement/Savings/Emergency Fund/Education/Vacation/House Down Payment/Wedding/Debt Payoff/Custom)",
              "targetAmount": number,
              "currentAmount": 0,
              "deadline": "YYYY-MM-DD",
              "monthlyContribution": number,
              "riskProfile": "string",
              "allocationStrategy": [
                { "assetClass": "string (e.g. Equity Mutual Funds, FD, Gold, PPF, Debt Funds, Liquid Funds)", "percentage": number }
              ],
              "milestones": ["string with ₹ amounts", "string", "string"]
            }

            Allocation rules:
            - Short term (< 3 yrs): FD, Liquid Funds
            - Medium (3-7 yrs): Hybrid Funds, Debt, Gold
            - Long (> 7 yrs): Equity Mutual Funds, Stocks, PPF

            Your personality: Professional, structured, concise, and financially intelligent.
            """;

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
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        messages.addAll(conversationHistory);

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
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
