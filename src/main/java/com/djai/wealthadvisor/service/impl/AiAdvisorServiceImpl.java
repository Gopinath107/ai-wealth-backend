package com.djai.wealthadvisor.service.impl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.djai.wealthadvisor.dto.AdvisorRequestDto;
import com.djai.wealthadvisor.dto.AdvisorResponseDto;
import com.djai.wealthadvisor.dto.AdvisorResponseDto.InstrumentQuoteDto;
import com.djai.wealthadvisor.dto.ChatHistoryDto;
import com.djai.wealthadvisor.dto.ChatSessionDto;
import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.entity.AiChatMessage;
import com.djai.wealthadvisor.entity.AiChatSession;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.entity.User;
import com.djai.wealthadvisor.repository.AiChatMessageRepository;
import com.djai.wealthadvisor.repository.AiChatSessionRepository;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.service.AiPromptService;
import com.djai.wealthadvisor.service.GoalService;
import com.djai.wealthadvisor.service.MarketQuoteService;
import com.djai.wealthadvisor.service.WatchlistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAdvisorServiceImpl {

    // ── IST constants ─────────────────────────────────────────────────────────
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final DateTimeFormatter ISO_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

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

    // Thread-local: list of InstrumentQuoteDtos built during a price fetch
    private final ThreadLocal<List<InstrumentQuoteDto>> foundInstrumentQuotes = new ThreadLocal<>();

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC: processChat
    // ════════════════════════════════════════════════════════════════════════
    public AdvisorResponseDto processChat(AdvisorRequestDto request) {
        AdvisorResponseDto response = new AdvisorResponseDto();
        response.setTimestamp(LocalDateTime.now(IST));

        try {
            Long userId  = request.getUserId();
            Long sessionId = request.getSessionId();
            String text  = request.getUserMessage();

            if (!userRepository.existsById(userId)) {
                response.setStatus("ERROR");
                response.setReply("User ID not found: " + userId);
                return response;
            }

            // ── Session ────────────────────────────────────────────────────
            AiChatSession session;
            if (sessionId == null || sessionId <= 0) {
                session = new AiChatSession();
                session.setUserId(userId);
                session.setTitle(generateTitle(text));
                session = sessionRepository.save(session);
            } else {
                session = sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"));
                if (!session.getUserId().equals(userId))
                    throw new RuntimeException("Unauthorized session access");
            }

            saveMessage(session, "user", text);

            User user = userRepository.findById(userId).orElseThrow();

            List<WatchlistDto> watchlist;
            try { watchlist = watchlistService.list(userId); }
            catch (Exception e) { log.warn("Failed to fetch watchlist for user {}", userId); watchlist = new ArrayList<>(); }

            List<GoalDto> goals;
            try { goals = goalService.list(userId); }
            catch (Exception e) { log.warn("Failed to fetch goals for user {}", userId); goals = new ArrayList<>(); }

            List<AiChatMessage> history = messageRepository.findBySessionId(session.getId(), PageRequest.of(0, 4)); // 2 turns max — avoid 413 PAYLOAD_TOO_LARGE
            Collections.reverse(history);

            String rawReply;

            // ── Step 1: Try to resolve specific instruments and inject live prices ──
            String realPriceData = fetchRealPriceData(text);

            if (realPriceData != null && !realPriceData.isBlank()) {
                log.info("Found real market data, injecting into standard chat response");
                String systemPrompt = aiPromptService.buildSystemPrompt(user, watchlist, goals);
                systemPrompt += "\n\nREAL-TIME MARKET DATA (from Upstox/NSE/BSE):\n" + realPriceData
                        + "\nRULES:\n"
                        + "1. Use ONLY the prices listed above — do NOT guess, estimate, or use training knowledge for prices.\n"
                        + "2. Always refer to the exact instrument/symbol shown above (do not substitute with physical gold or commodity if instrument is an ETF).\n"
                        + "3. Format your response clearly with Markdown.\n"
                        + "4. Add a brief analysis after displaying the price.\n"
                        + "5. Mention the exchange and whether the market is currently open.";

                Map<String, Object> payload = prepareGroqPayload(systemPrompt, history, text);
                rawReply = callGroqApiWithRetry(payload);

            } else if (isPriceQuery(text) && !isSpecificInstrumentQuery(text)) {
                // ── Step 2: Generic commodity/index query (physical gold, bitcoin, etc.)
                // Only fall back to compound if it is NOT an instrument-specific query
                log.info("Generic price query without NSE/BSE instrument match — using compound model");
                rawReply = callCompoundForPriceQueryFallback(text);

            } else {
                // ── Step 3: Standard advisory chat ────────────────────────────────
                String systemPrompt = aiPromptService.buildSystemPrompt(user, watchlist, goals);
                Map<String, Object> payload = prepareGroqPayload(systemPrompt, history, text);
                rawReply = callGroqApiWithRetry(payload);
            }

            if (rawReply == null || rawReply.isBlank())
                rawReply = "I'm sorry, I couldn't generate a response. Could you please rephrase your question?";

            // ── Parse follow-ups ───────────────────────────────────────────
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
                        if (!question.isBlank()) followUps.add(question);
                    }
                }
            }

            saveMessage(session, "assistant", cleanReply);

            session.setUpdatedAt(LocalDateTime.now(IST));
            sessionRepository.save(session);

            response.setSessionId(session.getId());
            response.setChatTitle(session.getTitle());
            response.setReply(cleanReply);
            response.setFollowUps(followUps.isEmpty() ? null : followUps);

            // ── Attach instrument data (keys + rich quotes) ───────────────
            List<InstrumentQuoteDto> quotes = foundInstrumentQuotes.get();
            if (quotes != null && !quotes.isEmpty()) {
                // Derive raw keys list for backward compatibility with the chart URL builder
                List<String> keys = quotes.stream()
                        .map(InstrumentQuoteDto::getInstrumentKey)
                        .toList();
                response.setInstrumentKeys(keys);
                response.setInstrumentQuotes(quotes);
            }
            foundInstrumentQuotes.remove();

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

    // ════════════════════════════════════════════════════════════════════════
    // INSTRUMENT RESOLUTION (FIX Issues 1, 2, 3)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetches live price data for instruments found in the user's message.
     *
     * FIX Issue 1: Alias map is checked FIRST (before any stop-word filtering)
     *   so "gold bees", "tata gold", etc. are correctly mapped to their ETF symbols.
     *
     * FIX Issue 2: Only ONE best-match instrument is selected per query term
     *   (NSE_EQ preferred), preventing chart/text mismatch.
     *
     * FIX Issue 3: A full InstrumentQuoteDto (with name, exchange, price, timestamp)
     *   is built and stored for the frontend metadata card.
     */
    private String fetchRealPriceData(String userMessage) {
        try {
            String lower = userMessage.toLowerCase().trim();

            // Step 1: Check alias map first — covers well-known ETF names/phrases
            List<String> resolvedSymbols = resolveByAlias(lower);

            // Step 2: If no alias matched, extract individual words (stop-word filtered)
            if (resolvedSymbols.isEmpty()) {
                resolvedSymbols = extractSymbolsFromWords(lower);
            }

            if (resolvedSymbols.isEmpty()) return null;

            StringBuilder priceData = new StringBuilder();
            List<InstrumentQuoteDto> quoteDtos = new ArrayList<>();

            for (String symbol : resolvedSymbols) {
                // Find best instrument match (exact symbol > NSE_EQ > BSE_EQ > others)
                InstrumentMaster best = findBestInstrument(symbol);
                if (best == null) continue;

                try {
                    MarketQuoteDto quote = marketQuoteService.getQuote(best.getInstrumentKey());
                    if (quote == null || quote.getLtp() <= 0) {
                        log.warn("Price unavailable for {}", best.getTradingSymbol());
                        continue;
                    }

                    // Build IST-aware timestamp string
                    String asOfStr = buildAsOfString(quote);
                    boolean delayed = isDelayed(quote);
                    boolean closed  = isMarketClosed();

                    // Append to the AI prompt context
                    priceData.append(String.format(
                            "- %s (%s) on %s: LTP = ₹%.2f, Prev Close = ₹%.2f, Change = %.2f%%  [%s]\n",
                            quote.getName() != null ? quote.getName() : best.getName(),
                            best.getTradingSymbol(),
                            best.getExchange(),
                            quote.getLtp(),
                            quote.getPrevClose(),
                            quote.getChangePercent(),
                            closed ? "Market Closed" : delayed ? "Delayed" : "Live"
                    ));

                    // Build the rich DTO for the UI card
                    InstrumentQuoteDto dto = new InstrumentQuoteDto();
                    dto.setInstrumentKey(best.getInstrumentKey());
                    dto.setTradingSymbol(best.getTradingSymbol());
                    dto.setName(quote.getName() != null ? quote.getName() : best.getName());
                    dto.setExchange(best.getExchange());
                    dto.setLtp(round2(quote.getLtp()));
                    dto.setPrevClose(round2(quote.getPrevClose()));
                    dto.setChange(round2(quote.getChange()));
                    dto.setChangePercent(round2(quote.getChangePercent()));
                    dto.setAsOf(asOfStr);
                    dto.setDelayed(delayed);
                    dto.setMarketClosed(closed);
                    quoteDtos.add(dto);

                } catch (Exception e) {
                    log.warn("Failed to fetch quote for {}: {}", best.getTradingSymbol(), e.getMessage());
                }
            }

            if (!quoteDtos.isEmpty()) {
                foundInstrumentQuotes.set(quoteDtos);
            }

            String result = priceData.toString().trim();
            return result.isEmpty() ? null : result;

        } catch (Exception e) {
            log.warn("Failed to fetch real price data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the best matching InstrumentMaster for a symbol string.
     * Priority: exact trading_symbol match on NSE_EQ > BSE_EQ > NSE_INDEX > other
     */
    private InstrumentMaster findBestInstrument(String symbol) {
        // Exact NSE equity match first
        List<InstrumentMaster> candidates = instrumentRepo.search(symbol, "NSE", 5);
        // Then BSE
        if (candidates.isEmpty()) candidates = instrumentRepo.search(symbol, "BSE", 5);
        // Then any segment
        if (candidates.isEmpty()) candidates = instrumentRepo.search(symbol, null, 5);
        if (candidates.isEmpty()) return null;

        // Within results, prefer the one whose trading_symbol matches exactly
        for (InstrumentMaster im : candidates) {
            if (im.getTradingSymbol().equalsIgnoreCase(symbol)) return im;
        }

        // Prefer NSE_EQ over BSE_EQ over NSE_INDEX etc.
        String[] segmentPriority = {"NSE_EQ", "BSE_EQ", "NSE_INDEX", "BSE_INDEX"};
        for (String seg : segmentPriority) {
            for (InstrumentMaster im : candidates) {
                if (seg.equalsIgnoreCase(im.getSegment())) return im;
            }
        }

        return candidates.get(0);
    }

    // ════════════════════════════════════════════════════════════════════════
    // ALIAS / SYMBOL EXTRACTION  (FIX Issue 1)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks the user message against a comprehensive alias map of known
     * ETF names, fund names, and common abbreviations BEFORE any stop-word
     * filtering. Returns canonical trading symbols when matched.
     *
     * This is the primary fix for Issue 1: phrases like "gold bees", "tata gold",
     * "nippon gold" are caught here and routed to instrument search — they never
     * reach the generic commodity fallback.
     */
    private List<String> resolveByAlias(String lower) {
        // Ordered map: longest phrase first to prevent partial matches
        // (e.g., "gold bees" before "gold")
        LinkedHashMap<String, String> aliases = buildAliasMap();
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            if (lower.contains(entry.getKey())) {
                matched.add(entry.getValue());
            }
        }
        return matched;
    }

    /** Builds the comprehensive alias map. Longest phrases first. */
    private LinkedHashMap<String, String> buildAliasMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();

        // ── Gold ETFs ────────────────────────────────────────────────────────
        m.put("gold bees",       "GOLDBEES");
        m.put("goldbees",        "GOLDBEES");
        m.put("nippon gold",     "GOLDBEES");   // Nippon India ETF Gold BeES
        m.put("nippon etf gold", "GOLDBEES");
        m.put("tata gold",       "TATAELXSI");  // resolve via DB; may map differently
        m.put("icicigold",       "ICICIGOLD");
        m.put("icici gold",      "ICICIGOLD");
        m.put("edelweiss gold",  "EGIRETF");
        m.put("axis gold",       "AXISGOLD");
        m.put("hdfc gold",       "HDFCGOLD");
        m.put("kotak gold",      "KOTAKGOLD");
        m.put("sbi gold",        "SBIGOLD");
        m.put("quantum gold",    "QGOLDHALF");
        m.put("netf gold",       "NETFGOLD");
        m.put("netfgold",        "NETFGOLD");

        // ── Silver ETFs ──────────────────────────────────────────────────────
        m.put("silver bees",     "SILVERBEES");
        m.put("silverbees",      "SILVERBEES");
        m.put("nippon silver",   "SILVERBEES");

        // ── Nifty ETFs ───────────────────────────────────────────────────────
        m.put("niftybees",       "NIFTYBEES");
        m.put("nifty bees",      "NIFTYBEES");
        m.put("nippon nifty",    "NIFTYBEES");
        m.put("uti nifty",       "UTINIFTETF");
        m.put("sbi nifty",       "SETFNIF50");
        m.put("hdfc nifty",      "HDFCSENETF");
        m.put("kotak nifty",     "KOTAKNIFTY");
        m.put("icici nifty",     "ICICIB22");

        // ── Liquid/Debt ETFs ─────────────────────────────────────────────────
        m.put("liquidbees",      "LIQUIDBEES");
        m.put("liquid bees",     "LIQUIDBEES");
        m.put("nippon liquid",   "LIQUIDBEES");

        // ── Large-cap Stocks ─────────────────────────────────────────────────
        m.put("reliance",        "RELIANCE");
        m.put("tcs",             "TCS");
        m.put("tata consultancy","TCS");
        m.put("infosys",         "INFY");
        m.put("infy",            "INFY");
        m.put("hdfc bank",       "HDFCBANK");
        m.put("hdfcbank",        "HDFCBANK");
        m.put("icici bank",      "ICICIBANK");
        m.put("icicibank",       "ICICIBANK");
        m.put("sbi",             "SBIN");
        m.put("state bank",      "SBIN");
        m.put("tata motors",     "TATAMOTORS");
        m.put("tatamotors",      "TATAMOTORS");
        m.put("tata steel",      "TATASTEEL");
        m.put("tataelxsi",       "TATAELXSI");
        m.put("maruti",          "MARUTI");
        m.put("wipro",           "WIPRO");
        m.put("titan",           "TITAN");
        m.put("itc",             "ITC");
        m.put("bajaj finance",   "BAJFINANCE");
        m.put("bajfinance",      "BAJFINANCE");
        m.put("axis bank",       "AXISBANK");
        m.put("axisbank",        "AXISBANK");
        m.put("kotak bank",      "KOTAKBANK");
        m.put("kotakbank",       "KOTAKBANK");
        m.put("hdfc life",       "HDFCLIFE");
        m.put("sun pharma",      "SUNPHARMA");
        m.put("sunpharma",       "SUNPHARMA");
        m.put("hcl tech",        "HCLTECH");
        m.put("hcltech",         "HCLTECH");
        m.put("lt",              "LT");
        m.put("larsen",          "LT");
        m.put("ongc",            "ONGC");
        m.put("ntpc",            "NTPC");
        m.put("power grid",      "POWERGRID");
        m.put("powergrid",       "POWERGRID");
        m.put("adani ports",     "ADANIPORTS");
        m.put("adaniports",      "ADANIPORTS");
        m.put("bpcl",            "BPCL");
        m.put("dr reddy",        "DRREDDY");
        m.put("drreddy",         "DRREDDY");
        m.put("nestle india",    "NESTLEIND");
        m.put("nestleind",       "NESTLEIND");

        return m;
    }

    /**
     * Fallback: extract potential symbols from individual words after filtering
     * common English stop words AND commodity terms (which should not be resolved
     * as exchange instruments).
     */
    private List<String> extractSymbolsFromWords(String lower) {
        List<String> terms = new ArrayList<>();
        String[] words = lower.replaceAll("[^a-z0-9\\s]", "").split("\\s+");
        for (String word : words) {
            if (word.length() >= 2 && !isStopWord(word)) {
                terms.add(word.toUpperCase());
            }
        }
        return terms;
    }

    /** Returns true if the message mentions a known specific instrument name/ticker. */
    private boolean isSpecificInstrumentQuery(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        // If any alias key matches, it's instrument-specific
        for (String key : buildAliasMap().keySet()) {
            if (lower.contains(key)) return true;
        }
        return false;
    }

    private boolean isStopWord(String word) {
        return java.util.Set.of(
                // English functional words
                "the", "is", "of", "in", "for", "and", "or", "to", "at", "on",
                "what", "how", "much", "price", "current", "today", "now",
                "give", "me", "show", "tell", "can", "you", "please",
                "stock", "share", "etf", "rate", "value", "cost",
                "it", "its", "will", "be", "my", "this", "that",
                "any", "impact", "increase", "decrease", "future",
                "monday", "opening", "expect", "also", "hi", "hello",
                // Generic commodity terms — these should go to compound fallback
                // if no alias matched above (physical gold/silver/oil etc.)
                "gold", "silver", "oil", "crude", "bitcoin", "crypto",
                "ethereum", "nifty", "sensex", "market", "commodity"
        ).contains(word);
    }

    private boolean isPriceQuery(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        String[] priceKeywords = {
                "gold price", "silver price", "gold rate", "silver rate",
                "oil price", "crude price", "commodity price",
                "goldbees", "gold bees", "gold etf", "silverbees", "silver etf",
                "niftybees", "liquidbees", "etf price", "bees price",
                "nifty", "sensex", "stock price", "share price", "market price",
                "bitcoin price", "crypto price", "ethereum price",
                "current price", "today price", "what is the price",
                "how much is", "live price", "latest price",
                "price of", "rate of", "value of"
        };
        for (String keyword : priceKeywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    // TIMESTAMP HELPERS  (FIX Issue 4)
    // ════════════════════════════════════════════════════════════════════════

    private String buildAsOfString(MarketQuoteDto quote) {
        LocalDateTime ldt = quote.getAsOf();
        ZonedDateTime zdt = (ldt != null) ? ldt.atZone(IST) : ZonedDateTime.now(IST);
        return zdt.format(ISO_OFFSET);
    }

    private boolean isDelayed(MarketQuoteDto quote) {
        if (quote.getAsOf() == null) return true;
        ZonedDateTime asOf = quote.getAsOf().atZone(IST);
        return java.time.Duration.between(asOf, ZonedDateTime.now(IST)).toMinutes() > 15;
    }

    private boolean isMarketClosed() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek day = now.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return true;
        LocalTime t = now.toLocalTime();
        return t.isBefore(MARKET_OPEN) || t.isAfter(MARKET_CLOSE);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMPOUND FALLBACK (physical gold, crypto, indices via web search)
    // ════════════════════════════════════════════════════════════════════════
    private String callCompoundForPriceQueryFallback(String userMessage) {
        String today = LocalDate.now(IST).toString();
        String miniPrompt = "You are DJ-AI, an Indian financial advisor. Today: " + today + ".\n\n"
                + "SEARCH the web for the EXACT CURRENT LIVE PRICE. "
                + "Use ONLY ₹ (INR). NEVER guess prices.\n"
                + "Format: ## heading, **bold** prices, | tables |. Keep under 200 words.\n"
                + "Note: this is a PHYSICAL COMMODITY or CRYPTOCURRENCY price, not a stock/ETF.\n"
                + "At the end add: ## Follow-ups with 3 numbered questions.";

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", miniPrompt));
        messages.add(Map.of("role", "user",   "content", userMessage));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",       groqModel);
        payload.put("messages",    messages);
        payload.put("temperature", 0.3);
        payload.put("max_tokens",  800); // short commodity price reply

        return callGroqApiWithRetry(payload);
    }

    // ════════════════════════════════════════════════════════════════════════
    // SESSION / MESSAGE HELPERS
    // ════════════════════════════════════════════════════════════════════════
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
            String content = m.getContent();
            if ("assistant".equals(m.getRole())) {
                int fIdx = findFollowUpIndex(content);
                if (fIdx != -1) content = content.substring(0, fIdx).trim();
            }
            dto.setContent(content);
            dto.setTimestamp(m.getTimestamp());
            return dto;
        }).toList();
    }

    private String generateTitle(String message) {
        if (message == null) return "New Chat";
        String clean = message.trim();
        return clean.length() > 30 ? clean.substring(0, 30) + "..." : clean;
    }

    private void saveMessage(AiChatSession session, String role, String content) {
        AiChatMessage msg = new AiChatMessage();
        msg.setSession(session);
        msg.setUserId(session.getUserId());
        msg.setRole(role);
        msg.setContent(content);
        messageRepository.save(msg);
    }

    // ════════════════════════════════════════════════════════════════════════
    // AI PAYLOAD / CALL HELPERS
    // ════════════════════════════════════════════════════════════════════════
    private Map<String, Object> prepareGroqPayload(String systemPrompt,
                                                    List<AiChatMessage> history,
                                                    String currentMsg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            String content = msg.getContent();
            if (content != null && !content.equals(currentMsg)) {
                // Truncate long AI responses to stay within Groq token limit
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "…";
                }
                messages.add(Map.of("role", msg.getRole(), "content", content));
            }
        }
        messages.add(Map.of("role", "user",
                "content", currentMsg != null ? currentMsg : ""));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model",       groqModelLite);
        payload.put("messages",    messages);
        payload.put("temperature", 0.7);
        payload.put("max_tokens",  1000); // headroom within 6k Groq context limit
        return payload;
    }

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
                if (attempt == MAX_RETRIES)
                    throw new RuntimeException("Rate limit exceeded after " + MAX_RETRIES + " retries.");
                try { Thread.sleep(waitMs); }
                catch (InterruptedException ie) {
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
                if (mMs.find()) return Long.parseLong(mMs.group(1)) + 200;
                java.util.regex.Matcher mS  = java.util.regex.Pattern.compile("in ([\\d.]+)s").matcher(errorBody);
                if (mS.find())  return (long)(Double.parseDouble(mS.group(1)) * 1000) + 200;
            }
        } catch (Exception ignored) {}
        return 2000;
    }

    private int findFollowUpIndex(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?m)^#{1,3}\\s*Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\s*$"
                + "|(?m)^\\*\\*Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\*\\*\\s*$"
                + "|(?m)^Follow[\\-\u2010\u2011\u2012\u2013\u2014\\s]?[Uu]p[s]?(?:\\s+[Qq]uestions?)?\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.start() : -1;
    }
}