package com.djai.wealthadvisor.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.djai.wealthadvisor.dto.AdvisorRequestDto;
import com.djai.wealthadvisor.dto.AdvisorResponseDto;
import com.djai.wealthadvisor.dto.ChatHistoryDto;
import com.djai.wealthadvisor.dto.ChatSessionDto;
import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.entity.AiChatMessage;
import com.djai.wealthadvisor.entity.AiChatSession;
import com.djai.wealthadvisor.entity.User;
import com.djai.wealthadvisor.repository.AiChatMessageRepository;
import com.djai.wealthadvisor.repository.AiChatSessionRepository;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.service.AiPromptService;
import com.djai.wealthadvisor.service.GoalService;
import com.djai.wealthadvisor.service.WatchlistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.djai.wealthadvisor.service.MarketQuoteService;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.entity.InstrumentMaster;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAdvisorServiceImpl {

    private final UserRepository userRepository;
    private final WatchlistService watchlistService;
    private final GoalService goalService;
    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;
    private final MarketQuoteService marketQuoteService;
    private final InstrumentMasterRepository instrumentRepo;

    @Value("${api.groq.key}")
    private String groqApiKey;

    @Value("${api.groq.url}")
    private String groqUrl;

    @Value("${api.groq.model}")
    private String groqModel; // groq/compound — only used as fallback for web search

    @Value("${api.groq.model.lite}")
    private String groqModelLite; // llama-3.3-70b-versatile — primary model for chat

    private final RestClient restClient = RestClient.create();
    private static final int MAX_RETRIES = 3;

    // Thread-local storage for instrument keys found during price queries
    private final ThreadLocal<List<String>> foundInstrumentKeys = new ThreadLocal<>();

    public AdvisorResponseDto processChat(AdvisorRequestDto request) {
        AdvisorResponseDto response = new AdvisorResponseDto();
        response.setTimestamp(LocalDateTime.now());

        try {
            Long userId = request.getUserId();
            Long sessionId = request.getSessionId();
            String text = request.getUserMessage();

            if (!userRepository.existsById(userId)) {
                response.setStatus("ERROR");
                response.setReply("User ID not found: " + userId);
                return response;
            }

            AiChatSession session;
            if (sessionId == null || sessionId <= 0) {
                session = new AiChatSession();
                session.setUserId(userId);
                session.setTitle(generateTitle(text));
                session = sessionRepository.save(session);
            } else {
                session = sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"));

                if (!session.getUserId().equals(userId)) {
                    throw new RuntimeException("Unauthorized session access");
                }
            }

            saveMessage(session, "user", text);

            User user = userRepository.findById(userId).orElseThrow();

            List<WatchlistDto> watchlist;
            try {
                watchlist = watchlistService.list(userId);
            } catch (Exception e) {
                log.warn("Failed to fetch watchlist for user {}", userId);
                watchlist = new ArrayList<>();
            }

            List<GoalDto> goals;
            try {
                goals = goalService.list(userId);
            } catch (Exception e) {
                log.warn("Failed to fetch goals for user {}", userId);
                goals = new ArrayList<>();
            }

            List<AiChatMessage> history = messageRepository.findBySessionId(session.getId(), PageRequest.of(0, 10));
            Collections.reverse(history);

            String rawReply;

            // Route price/market queries to compound model (has web search for real-time
            // data)
            if (isPriceQuery(text)) {
                log.info("Price query detected, using compound model with web search");
                rawReply = callCompoundForPriceQuery(text);
            } else {
                String systemPrompt = aiPromptService.buildSystemPrompt(user, watchlist, goals);
                Map<String, Object> payload = prepareGroqPayload(systemPrompt, history, text);
                rawReply = callGroqApiWithRetry(payload);
            }

            // Validate response
            if (rawReply == null || rawReply.isBlank()) {
                rawReply = "I'm sorry, I couldn't generate a response. Could you please rephrase your question?";
            }

            // Parse follow-up suggestions from the AI response
            List<String> followUps = new ArrayList<>();
            String cleanReply = rawReply;
            int followUpIdx = findFollowUpIndex(rawReply);
            if (followUpIdx != -1) {
                String followUpSection = rawReply.substring(followUpIdx);
                cleanReply = rawReply.substring(0, followUpIdx).trim();
                for (String line : followUpSection.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.matches("^\\d+\\.\\s+.+") || trimmed.matches("^[-*]\\s+.+")) {
                        String question = trimmed.replaceFirst("^(\\d+\\.\\s+|[-*]\\s+)", "").trim();
                        if (!question.isBlank()) {
                            followUps.add(question);
                        }
                    }
                }
            }

            // Save CLEAN reply (without follow-ups) to DB to prevent duplicate rendering
            saveMessage(session, "assistant", cleanReply);

            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);

            response.setSessionId(session.getId());
            response.setChatTitle(session.getTitle());
            response.setReply(cleanReply);
            response.setFollowUps(followUps.isEmpty() ? null : followUps);

            // Attach instrument keys for chart rendering (if price query found instruments)
            List<String> instKeys = foundInstrumentKeys.get();
            if (instKeys != null && !instKeys.isEmpty()) {
                response.setInstrumentKeys(instKeys);
            }
            foundInstrumentKeys.remove();

            response.setStatus("SUCCESS");

        } catch (Exception e) {
            log.error("Chat Error", e);
            response.setStatus("ERROR");
            response.setReply(
                    "I'm having trouble connecting to the AI service right now. Please try again in a moment.");
        }
        return response;
    }

    // ════════════════════════════════════════════════════════════════
    // Helper: find follow-up section in AI response
    // Handles: ## Follow-ups, ## Follow‑ups (non-breaking hyphen),
    // **Follow-up Questions**, Follow-Ups, etc.
    // ════════════════════════════════════════════════════════════════
    private int findFollowUpIndex(String text) {
        // Use regex to match all dash variants (-, ‑, –, —) and common headings
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?m)^#{1,3}\\s*Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\s*$" +
                        "|" +
                        "(?m)^\\*\\*Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\*\\*\\s*$"
                        +
                        "|" +
                        "(?m)^Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.start();
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════
    // Helper: detect price/market queries that need web search
    // ════════════════════════════════════════════════════════════════
    private boolean isPriceQuery(String message) {
        if (message == null)
            return false;
        String lower = message.toLowerCase();
        String[] priceKeywords = {
                // Gold & commodities
                "gold price", "silver price", "gold rate", "silver rate",
                "oil price", "crude price", "commodity price",
                // ETFs & mutual funds
                "goldbees", "gold bees", "gold etf", "silverbees", "silver etf",
                "niftybees", "liquidbees", "etf price", "bees price",
                // Stock market
                "nifty", "sensex", "stock price", "share price", "market price",
                // Crypto
                "bitcoin price", "crypto price", "ethereum price",
                // Generic price queries
                "current price", "today price", "what is the price",
                "how much is", "live price", "latest price",
                "price of", "rate of", "value of"
        };
        for (String keyword : priceKeywords) {
            if (lower.contains(keyword))
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // Price query handler: real Upstox data first, compound fallback
    // ════════════════════════════════════════════════════════════════
    private String callCompoundForPriceQuery(String userMessage) {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        // Step 1: Try to find real instrument data from Upstox
        String realPriceData = fetchRealPriceData(userMessage);

        if (realPriceData != null && !realPriceData.isBlank()) {
            // We have REAL data — use lite model to format a nice response
            log.info("Found real market data, using lite model with injected prices");
            String prompt = "You are DJ-AI, an Indian financial advisor. Today: " + today + ".\n\n"
                    + "REAL-TIME MARKET DATA (from Upstox/NSE/BSE — these are VERIFIED live prices):\n"
                    + realPriceData + "\n\n"
                    + "RULES:\n"
                    + "1. Use ONLY the prices provided above. Do NOT change or guess different prices.\n"
                    + "2. Format with Markdown: ## heading, **bold** prices, | tables |.\n"
                    + "3. Add brief analysis (2-3 sentences) about the stock/ETF.\n"
                    + "4. Keep response under 200 words.\n"
                    + "5. All prices in \u20b9 (INR).\n"
                    + "At the end add: ## Follow-ups with 3 numbered questions.";

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", prompt));
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> payload = new HashMap<>();
            payload.put("model", groqModelLite);
            payload.put("messages", messages);
            payload.put("temperature", 0.3);
            payload.put("max_tokens", 1500);

            return callGroqApiWithRetry(payload);
        }

        // Step 2: No instrument found — fall back to compound model (web search)
        // This handles physical gold, silver, crypto, etc.
        log.info("No instrument found, falling back to compound model web search");
        String miniPrompt = "You are DJ-AI, an Indian financial advisor. Today: " + today + ".\n\n"
                + "SEARCH the web for the EXACT CURRENT LIVE PRICE. "
                + "Use ONLY \u20b9 (INR). NEVER guess prices.\n"
                + "Format: ## heading, **bold** prices, | tables |. Keep under 200 words.\n"
                + "At the end add: ## Follow-ups with 3 numbered questions.";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", miniPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.3);
        payload.put("max_tokens", 1500);

        return callGroqApiWithRetry(payload);
    }

    // ════════════════════════════════════════════════════════════════
    // Extract stock/ETF symbols from message and fetch real Upstox data
    // ════════════════════════════════════════════════════════════════
    private String fetchRealPriceData(String userMessage) {
        try {
            String lower = userMessage.toLowerCase().trim();

            // Known symbol mappings for common queries
            String[] searchTerms = extractSearchTerms(lower);

            StringBuilder priceData = new StringBuilder();
            List<String> collectedKeys = new ArrayList<>();
            for (String term : searchTerms) {
                List<InstrumentMaster> instruments = instrumentRepo.search(term, null, 3);
                if (!instruments.isEmpty()) {
                    for (InstrumentMaster inst : instruments) {
                        try {
                            var quote = marketQuoteService.getQuote(inst.getInstrumentKey());
                            if (quote != null && quote.getLtp() > 0) {
                                priceData.append(String.format(
                                        "- %s (%s) on %s: LTP = \u20b9%.2f, Prev Close = \u20b9%.2f, Change = %.2f%%\n",
                                        quote.getName() != null ? quote.getName() : inst.getTradingSymbol(),
                                        inst.getTradingSymbol(),
                                        inst.getExchange(),
                                        quote.getLtp(),
                                        quote.getPrevClose(),
                                        quote.getChangePercent()));
                                collectedKeys.add(inst.getInstrumentKey());
                            }
                        } catch (Exception e) {
                            log.warn("Failed to fetch quote for {}: {}", inst.getTradingSymbol(), e.getMessage());
                        }
                    }
                }
            }

            // Store found instrument keys for chart rendering
            if (!collectedKeys.isEmpty()) {
                foundInstrumentKeys.set(collectedKeys);
            }

            String result = priceData.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("Failed to fetch real price data: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Extract potential search terms from user message
    // ════════════════════════════════════════════════════════════════
    private String[] extractSearchTerms(String lower) {
        // Known aliases
        Map<String, String> aliases = new HashMap<>();
        aliases.put("goldbees", "GOLDBEES");
        aliases.put("gold bees", "GOLDBEES");
        aliases.put("silverbees", "SILVERBEES");
        aliases.put("silver bees", "SILVERBEES");
        aliases.put("niftybees", "NIFTYBEES");
        aliases.put("nifty bees", "NIFTYBEES");
        aliases.put("liquidbees", "LIQUIDBEES");
        aliases.put("reliance", "RELIANCE");
        aliases.put("tcs", "TCS");
        aliases.put("infosys", "INFY");
        aliases.put("infy", "INFY");
        aliases.put("hdfc bank", "HDFCBANK");
        aliases.put("hdfcbank", "HDFCBANK");
        aliases.put("icici bank", "ICICIBANK");
        aliases.put("sbi", "SBIN");
        aliases.put("tatamotors", "TATAMOTORS");
        aliases.put("tata motors", "TATAMOTORS");
        aliases.put("itc", "ITC");
        aliases.put("wipro", "WIPRO");
        aliases.put("titan", "TITAN");

        List<String> terms = new ArrayList<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (lower.contains(entry.getKey())) {
                terms.add(entry.getValue());
            }
        }

        // If no alias matched, try to extract capitalized words or symbols
        if (terms.isEmpty()) {
            // Try each word as a potential symbol
            String[] words = lower.replaceAll("[^a-z0-9\\s]", "").split("\\s+");
            for (String word : words) {
                if (word.length() >= 2 && !isStopWord(word)) {
                    terms.add(word.toUpperCase());
                }
            }
        }

        return terms.toArray(new String[0]);
    }

    private boolean isStopWord(String word) {
        return java.util.Set.of(
                "the", "is", "of", "in", "for", "and", "or", "to", "at", "on",
                "what", "how", "much", "price", "current", "today", "now",
                "give", "me", "show", "tell", "can", "you", "please",
                "stock", "share", "etf", "rate", "value", "cost",
                // Commodities — these are NOT instrument symbols; they should
                // go to compound model for physical market prices
                "gold", "silver", "oil", "crude", "bitcoin", "crypto",
                "ethereum", "nifty", "sensex", "market", "commodity",
                "it", "its", "will", "be", "my", "this", "that",
                "any", "impact", "increase", "decrease", "future",
                "monday", "opening", "expect", "also", "hi", "hello").contains(word);
    }

    public List<ChatSessionDto> getUserSessions(Long userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(s -> {
                    ChatSessionDto dto = new ChatSessionDto();
                    dto.setSessionId(s.getId());
                    dto.setTitle(s.getTitle());
                    dto.setLastActive(s.getUpdatedAt());
                    return dto;
                }).toList();
    }

    public List<ChatHistoryDto> getSessionMessages(Long sessionId) {
        List<AiChatMessage> msgs = messageRepository.findBySessionId(sessionId, PageRequest.of(0, 50));
        List<AiChatMessage> sorted = new ArrayList<>(msgs);
        Collections.reverse(sorted);

        return sorted.stream().map(m -> {
            ChatHistoryDto dto = new ChatHistoryDto();
            dto.setId(m.getId());
            dto.setRole(m.getRole());
            // Strip follow-up section from historical assistant messages (safety net)
            String content = m.getContent();
            if ("assistant".equals(m.getRole())) {
                int fIdx = findFollowUpIndex(content);
                if (fIdx != -1) {
                    content = content.substring(0, fIdx).trim();
                }
            }
            dto.setContent(content);
            dto.setTimestamp(m.getTimestamp());
            return dto;
        }).toList();
    }

    private String generateTitle(String message) {
        if (message == null)
            return "New Chat";
        String clean = message.trim();
        if (clean.length() > 30)
            return clean.substring(0, 30) + "...";
        return clean;
    }

    private void saveMessage(AiChatSession session, String role, String content) {
        AiChatMessage msg = new AiChatMessage();
        msg.setSession(session);
        msg.setUserId(session.getUserId());
        msg.setRole(role);
        msg.setContent(content);
        messageRepository.save(msg);
    }

    private Map<String, Object> prepareGroqPayload(String systemPrompt, List<AiChatMessage> history,
            String currentMsg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            if (!msg.getContent().equals(currentMsg)) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content", currentMsg));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModelLite); // Use lite model — compound crashes with large system prompts
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1500);
        return payload;
    }

    // ════════════════════════════════════════════════════════════════
    // API CALL — with retry on 429 rate limit
    // ════════════════════════════════════════════════════════════════
    private String callGroqApiWithRetry(Map<String, Object> payload) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String body = restClient.post().uri(groqUrl)
                        .header("Authorization", "Bearer " + groqApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload).retrieve().body(String.class);
                JsonNode root = objectMapper.readTree(body);
                return root.path("choices").get(0).path("message").path("content").asText();
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                long waitMs = parseRetryDelay(e.getResponseBodyAsString());
                log.warn("Advisor rate limited (attempt {}/{}). Waiting {}ms...", attempt, MAX_RETRIES, waitMs);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Rate limit exceeded after " + MAX_RETRIES + " retries.");
                }
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during rate limit wait");
                }
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                log.error("AI API error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new RuntimeException("AI API error: " + e.getStatusCode());
            } catch (Exception e) {
                log.error("External AI API Error", e);
                throw new RuntimeException("AI Service is unreachable: " + e.getMessage());
            }
        }
        throw new RuntimeException("Failed after " + MAX_RETRIES + " retries");
    }

    private long parseRetryDelay(String errorBody) {
        try {
            if (errorBody != null) {
                java.util.regex.Matcher mMs = java.util.regex.Pattern.compile("in (\\d+)ms").matcher(errorBody);
                if (mMs.find())
                    return Long.parseLong(mMs.group(1)) + 200;
                java.util.regex.Matcher mS = java.util.regex.Pattern.compile("in ([\\d.]+)s").matcher(errorBody);
                if (mS.find())
                    return (long) (Double.parseDouble(mS.group(1)) * 1000) + 200;
            }
        } catch (Exception ignored) {
        }
        return 2000;
    }
}