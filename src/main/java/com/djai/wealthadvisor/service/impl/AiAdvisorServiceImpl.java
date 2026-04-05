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
import org.springframework.web.client.ResourceAccessException;

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
    // Injected Spring bean (AppConfig) — has 15s connect / 90s read timeout
    private final RestClient restClient;

    @Value("${api.groq.key}")
    private String groqApiKey;

    @Value("${api.groq.url}")
    private String groqUrl;

    // compound-beta supports real-time web search via Groq
    @Value("${api.groq.model}")
    private String groqCompoundModel;

    // llama-3.3-70b-versatile — fast model for standard chat
    @Value("${api.groq.model.lite}")
    private String groqModelLite;

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

            // Step 1: Always try to identify listed NSE/BSE instruments for chart display
            String realPriceData = fetchRealPriceData(text);

            if (realPriceData != null && !realPriceData.isBlank()) {
                // Instruments found in our DB — inject the live Upstox quote into the prompt
                log.info("Found real market data for instruments, injecting into chat context");
                String systemPrompt = aiPromptService.buildSystemPrompt(user, watchlist, goals);
                systemPrompt += "\n\nREAL-TIME MARKET DATA (live from Upstox/NSE/BSE):\n" + realPriceData
                        + "\nINSTRUCTIONS:\n1. Use these verified live prices as the primary source.\n"
                        + "2. Format your response clearly with Markdown. Bold all prices.\n"
                        + "3. Add a brief analysis and sentiment.";

                Map<String, Object> payload = prepareGroqPayload(systemPrompt, history, text);
                rawReply = callGroqApiWithRetry(payload);

            } else if (isRealTimeQuery(text)) {
                // Step 2: Query requires live data (news, events, prices not in our DB).
                // Use compound-beta which has built-in Groq web search.
                log.info("Real-time/news query detected — routing to compound-beta with web search");
                rawReply = callCompoundWithWebSearch(text, user, watchlist, goals);

            } else {
                // Step 3: Standard conversational chat — fast Llama model, no search needed
                log.info("Standard chat query — using {} model", groqModelLite);
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
                    "I'm having trouble connecting to the AI service right now. " +
                            "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
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
    // Detect queries that require real-time web data
    // (news, current events, live prices outside our instrument DB)
    // ════════════════════════════════════════════════════════════════
    private boolean isRealTimeQuery(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        // Price / commodity queries
        String[] keywords = {
                // Prices
                "gold price", "silver price", "gold rate", "silver rate",
                "oil price", "crude price", "commodity price",
                "nifty", "sensex", "stock price", "share price", "market price",
                "bitcoin price", "crypto price", "ethereum price", "btc",
                "current price", "today price", "what is the price",
                "how much is", "live price", "latest price", "price of", "rate of",
                "goldbees", "gold etf", "silverbees", "niftybees", "etf price",
                // News & current events
                "news", "latest news", "today news", "breaking", "update",
                "what happened", "what's happening", "current", "recent",
                "market today", "market now", "market open", "market close",
                "market outlook", "market analysis", "today's market",
                // Predictions & analysis requiring fresh data
                "monday opening", "tomorrow", "next week", "forecast",
                "impact", "affect", "effect of", "because of",
                "tariff", "rbi", "fed", "interest rate", "inflation",
                "budget", "election", "geopolitical", "war", "sanctions",
                "earnings", "result", "quarterly", "q1", "q2", "q3", "q4",
                "ipo", "fii", "dii", "fpi", "mutual fund nav"
        };
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // Compound-beta: Groq's web-search model for real-time answers
    // Handles news, prices, events — anything needing live internet data
    // ════════════════════════════════════════════════════════════════
    private String callCompoundWithWebSearch(String userMessage, User user,
            List<WatchlistDto> watchlist, List<GoalDto> goals) {
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        // Compact user context summary (to keep tokens low for compound-beta)
        StringBuilder ctx = new StringBuilder();
        ctx.append("User: ").append(user.getFullName());
        if (user.getCashBalance() != null) {
            ctx.append(" | Cash: \u20b9").append(user.getCashBalance());
        }
        if (watchlist != null && !watchlist.isEmpty()) {
            ctx.append(" | Watching: ");
            watchlist.stream().limit(5).forEach(w ->
                ctx.append(w.getTradingSymbol()).append("(").append(w.getLtp()).append(") "));
        }

        String systemPrompt = "You are DJ-AI, a real-time Indian financial advisor. Today: " + today + ".\n"
                + "User context: " + ctx + "\n\n"
                + "CRITICAL: Search the web for the LATEST, REAL-TIME information to answer this question.\n"
                + "- Always use \u20b9 (INR) for Indian prices.\n"
                + "- Cite actual current data — never guess or use old information.\n"
                + "- If asking about news/events: summarize the key facts and their market impact.\n"
                + "- If asking about prices: give exact current values with % change.\n"
                + "- Format: ## heading, **bold** numbers/prices, | Markdown tables | for data.\n"
                + "- Keep response under 250 words before Follow-ups.\n"
                + "- End every response with: ## Follow-ups\n  (3-4 numbered follow-up questions)";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqCompoundModel); 
        payload.put("messages", messages);
        payload.put("temperature", 0.2);  
        payload.put("max_tokens", 2048);
        
        // Enable web search functionalities for the compound model
        Map<String, Object> enabledTools = new HashMap<>();
        enabledTools.put("enabled_tools", java.util.Arrays.asList("web_search", "visit_website"));
        payload.put("compound_custom", Map.of("tools", enabledTools));

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
            String content = msg.getContent();
            if (content != null && !content.equals(currentMsg)) {
                messages.add(Map.of("role", msg.getRole(), "content", content));
            }
        }
        messages.add(Map.of("role", "user", "content", currentMsg != null ? currentMsg : ""));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModelLite); // Use lite model — compound crashes with large system prompts
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens", 1500);
        return payload;
    }

    // ════════════════════════════════════════════════════════════════
    // API CALL — with retry on 429 (rate limit) and transient I/O errors
    // ════════════════════════════════════════════════════════════════
    private String callGroqApiWithRetry(Map<String, Object> payload) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String body = restClient.post().uri(groqUrl)
                        .header("Authorization", "Bearer " + groqApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload).retrieve().body(String.class);
                JsonNode root = objectMapper.readTree(body);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    return choices.get(0).path("message").path("content").asText();
                }
                // Log unexpected response shape
                log.warn("Unexpected Groq response shape: {}", body.length() > 500 ? body.substring(0, 500) : body);
                throw new RuntimeException("Invalid response from Groq API");

            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                long waitMs = parseRetryDelay(e.getResponseBodyAsString());
                log.warn("Advisor rate limited (attempt {}/{}). Waiting {}ms...", attempt, MAX_RETRIES, waitMs);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Groq rate limit exceeded after " + MAX_RETRIES + " retries.");
                }
                sleepSafe(waitMs);

            } catch (ResourceAccessException e) {
                // Network/IO error (connection closed, timeout, etc.) — retry with backoff
                long backoffMs = (long) Math.pow(2, attempt) * 1000L; // 2s, 4s, 8s
                log.warn("Groq I/O error on attempt {}/{}: {}. Retrying in {}ms...",
                        attempt, MAX_RETRIES, e.getMessage(), backoffMs);
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("Groq connection failed after " + MAX_RETRIES
                            + " retries: " + e.getMessage());
                }
                sleepSafe(backoffMs);

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                log.error("Groq API HTTP error: {} — {}", e.getStatusCode(),
                        e.getResponseBodyAsString().length() > 300
                                ? e.getResponseBodyAsString().substring(0, 300)
                                : e.getResponseBodyAsString());
                throw new RuntimeException("Groq API error: " + e.getStatusCode());

            } catch (Exception e) {
                log.error("Unexpected Groq API error (attempt {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());
                if (attempt == MAX_RETRIES) {
                    throw new RuntimeException("AI Service error: " + e.getMessage());
                }
                sleepSafe(2000L * attempt);
            }
        }
        throw new RuntimeException("Failed after " + MAX_RETRIES + " retries");
    }

    private void sleepSafe(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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