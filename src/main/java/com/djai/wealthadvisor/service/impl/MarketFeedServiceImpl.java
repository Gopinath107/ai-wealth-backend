package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.dto.MarketFeedRowDto;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.dto.MarketCapDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.MarketCapService;
import com.djai.wealthadvisor.service.MarketFeedService;
import com.djai.wealthadvisor.service.MarketQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketFeedServiceImpl implements MarketFeedService {

    private final MarketQuoteService marketQuoteService;
    private final MarketCapService marketCapService;                 // ✅ NEW
    private final InstrumentMasterRepository instrumentRepo;

    @Value("${market.feed.defaults:}")
    private String defaultSymbolsCsv;

    private volatile CacheEntry cache;

    @Override
    public List<MarketFeedRowDto> getFeed(int limit, String q, boolean includeMarketCap) {
        int lim = normalizeLimit(limit);

        String query = (q == null) ? "" : q.trim();
        String cacheKey = lim + "|" + query.toLowerCase() + "|mcap=" + includeMarketCap;

        CacheEntry c = cache;
        long now = System.currentTimeMillis();
        if (c != null && c.key.equals(cacheKey) && (now - c.timeMs) < 2000) { // 2 sec cache
            return c.data;
        }

        List<String> keys = query.isBlank()
                ? resolveDefaultKeys(lim)
                : searchKeys(query, lim);

        if (keys.isEmpty()) {
            cache = new CacheEntry(cacheKey, now, List.of());
            return List.of();
        }

        // 1) Upstox batch quotes
        List<MarketQuoteDto> quotes = marketQuoteService.getQuotes(keys);

        // 2) Market caps (batch) — only when requested
        Map<String, MarketCapDto> caps = includeMarketCap
                ? marketCapService.getMarketCaps(keys)
                : Map.of();

        // 3) Build feed rows in same order as quotes/keys
        List<MarketFeedRowDto> rows = quotes.stream()
                .map(qd -> toRow(qd, caps.get(qd.getInstrumentKey())))
                .collect(Collectors.toList());

        cache = new CacheEntry(cacheKey, now, rows);
        return rows;
    }

    private MarketFeedRowDto toRow(MarketQuoteDto q, MarketCapDto cap) {
        Long marketCap = (cap == null) ? null : cap.marketCap();
        String currency = (cap == null) ? null : cap.currency();

        return new MarketFeedRowDto(
                q.getInstrumentKey(),
                q.getTradingSymbol(),
                q.getName(),
                q.getExchange(),
                round2(q.getLtp()),
                round2(q.getPrevClose()),
                round2(q.getChange()),
                round2(q.getChangePercent()),
                marketCap,
                currency,
                q.getAsOf()
        );
    }

    private List<String> resolveDefaultKeys(int limit) {
        List<String> symbols = parseCsv(defaultSymbolsCsv);
        if (symbols.isEmpty()) return List.of();

        List<String> keys = new ArrayList<>();
        for (String sym : symbols) {
            Optional<InstrumentMaster> nse = instrumentRepo
                    .findFirstByTradingSymbolIgnoreCaseAndExchangeIgnoreCase(sym, "NSE");

            if (nse.isPresent()) {
                keys.add(nse.get().getInstrumentKey());
            } else {
                List<InstrumentMaster> hits = instrumentRepo.search(sym, null, 1);
                if (!hits.isEmpty()) keys.add(hits.get(0).getInstrumentKey());
            }

            if (keys.size() >= limit) break;
        }

        return keys.stream().distinct().toList();
    }

    private List<String> searchKeys(String q, int limit) {
        List<InstrumentMaster> hits = instrumentRepo.search(q, null, limit);

        return hits.stream()
                .filter(im -> "NSE_EQ".equals(im.getSegment()) || "BSE_EQ".equals(im.getSegment()))
                .map(InstrumentMaster::getInstrumentKey)
                .distinct()
                .limit(limit)
                .toList();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) return 10;
        return Math.min(limit, 50);
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record CacheEntry(String key, long timeMs, List<MarketFeedRowDto> data) {}
}
