package com.djai.wealthadvisor.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.djai.wealthadvisor.dto.InstrumentSearchDto;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.entity.User;
import com.djai.wealthadvisor.entity.WatchlistItem;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.repository.WatchlistItemRepository;
import com.djai.wealthadvisor.service.InstrumentService;
import com.djai.wealthadvisor.service.MarketQuoteService;
import com.djai.wealthadvisor.service.WatchlistService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistItemRepository watchlistRepo;
    private final UserRepository userRepository;
    private final InstrumentService instrumentService;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final MarketQuoteService marketQuoteService;

    @Override
    public WatchlistDto add(WatchlistDto req) {
        if (req == null || req.getUserId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        Long userId = req.getUserId();

        String query = (req.getQuery() != null) ? req.getQuery().trim() : "";
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required (example: HINDUNILVR)");
        }

        // Fetch user email => user_key (NOT NULL in your table)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + userId));
        String userKey = user.getEmail();

        // Resolve instrument details
        Resolved r = resolveQuery(query);

        // Avoid duplicates
        Optional<WatchlistItem> existing = watchlistRepo.findByUserIdAndInstrumentKey(userId, r.instrumentKey);

        WatchlistItem item;
        if (existing.isPresent()) {
            item = existing.get();

            // Safety: if old row has null userKey, fix it
            if (item.getUserKey() == null || item.getUserKey().isBlank()) {
                item.setUserKey(userKey);
                item = watchlistRepo.save(item);
            }
        } else {
            WatchlistItem w = new WatchlistItem();
            w.setUserId(userId);
            w.setUserKey(userKey);
            w.setInstrumentKey(r.instrumentKey);
            w.setTradingSymbol(r.tradingSymbol);
            w.setName(r.name);
            w.setExchange(r.exchange);
            item = watchlistRepo.save(w);
        }

        MarketQuoteDto q = safeQuote(item.getInstrumentKey());
        return toDto(item, q);
    }

    @Override
    public List<WatchlistDto> list(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        // Validate user exists (better error than returning empty silently)
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + userId));

        List<WatchlistItem> items = watchlistRepo.findByUserIdOrderByCreatedAtDesc(userId);
        if (items.isEmpty()) return List.of();

        List<String> keys = items.stream().map(WatchlistItem::getInstrumentKey).toList();
        List<MarketQuoteDto> quotes = marketQuoteService.getQuotes(keys);

        Map<String, MarketQuoteDto> quoteMap = quotes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(MarketQuoteDto::getInstrumentKey, q -> q, (a, b) -> a));

        List<WatchlistDto> out = new ArrayList<>(items.size());
        for (WatchlistItem item : items) {
            out.add(toDto(item, quoteMap.get(item.getInstrumentKey())));
        }
        return out;
    }

    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        if (id == null) throw new IllegalArgumentException("id is required");
        if (userId == null) throw new IllegalArgumentException("userId is required");

        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid userId: " + userId));

        long deleted = watchlistRepo.deleteByIdAndUserId(id, userId);
        if (deleted == 0) {
            throw new IllegalArgumentException("Watchlist item not found for id=" + id + " and userId=" + userId);
        }
    }
    // ----------------- helpers -----------------

    private Resolved resolveQuery(String query) {
        // Case 1: query is instrumentKey
        if (query.contains("|")) {
            String normalizedKey = normalizeInstrumentKey(query);
            InstrumentMaster im = instrumentMasterRepository.findById(normalizedKey)
                    .orElseThrow(() -> new IllegalArgumentException("Instrument not found for key: " + normalizedKey));
            return new Resolved(im.getInstrumentKey(), im.getTradingSymbol(), im.getName(), im.getExchange());
        }

        // Case 2: resolve by trading symbol / company name
        InstrumentSearchDto resolved = instrumentService.resolve(query, null);
        if (resolved == null || resolved.instrumentKey() == null || resolved.instrumentKey().isBlank()) {
            throw new IllegalArgumentException("No matching instrument found for: " + query);
        }
        return new Resolved(resolved.instrumentKey(), resolved.tradingSymbol(), resolved.name(), resolved.exchange());
    }

    private MarketQuoteDto safeQuote(String instrumentKey) {
        try {
            return marketQuoteService.getQuote(instrumentKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private WatchlistDto toDto(WatchlistItem item, MarketQuoteDto q) {
        WatchlistDto dto = new WatchlistDto();

        // ✅ show userId in response
        dto.setUserId(item.getUserId());

        dto.setId(item.getId());
        dto.setInstrumentKey(item.getInstrumentKey());
        dto.setTradingSymbol(item.getTradingSymbol());
        dto.setName(item.getName());
        dto.setExchange(item.getExchange());
        dto.setCreatedAt(item.getCreatedAt());

        if (q != null) {
            dto.setLtp(q.getLtp());
            dto.setPrevClose(q.getPrevClose());
            dto.setChange(q.getChange());
            dto.setChangePercent(q.getChangePercent());
            dto.setAsOf(q.getAsOf());
        }
        return dto;
    }

    private static String normalizeInstrumentKey(String instrumentKey) {
        String trimmed = instrumentKey.trim();
        int pipe = trimmed.indexOf('|');
        if (pipe <= 0) return trimmed;

        String left = trimmed.substring(0, pipe).toUpperCase(Locale.ROOT);
        String right = trimmed.substring(pipe + 1);

        if (left.endsWith("_EQ") && right.toUpperCase(Locale.ROOT).startsWith("INE")) {
            right = right.toUpperCase(Locale.ROOT);
        }
        return left + "|" + right;
    }

    private record Resolved(String instrumentKey, String tradingSymbol, String name, String exchange) {}
}
