package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.dto.MarketMoverDto;
import com.djai.wealthadvisor.dto.MarketQuoteDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.MarketMoverService;
import com.djai.wealthadvisor.service.MarketQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MarketMoverServiceImpl implements MarketMoverService {

    private final MarketQuoteService marketQuoteService;
    private final InstrumentMasterRepository instrumentRepo;

    @Value("${market.universe.symbols}")
    private String universeSymbolsCsv;

    private volatile CacheEntry cache; // 2s cache

    @Override
    public List<MarketMoverDto> topGainers(int limit) {
        List<MarketMoverDto> movers = getUniverseMovers();
        return movers.stream()
                .sorted(Comparator.comparingDouble(MarketMoverDto::changePercent).reversed())
                .limit(normLimit(limit))
                .collect(Collectors.toList());
    }

    @Override
    public List<MarketMoverDto> topLosers(int limit) {
        List<MarketMoverDto> movers = getUniverseMovers();
        return movers.stream()
                .sorted(Comparator.comparingDouble(MarketMoverDto::changePercent))
                .limit(normLimit(limit))
                .collect(Collectors.toList());
    }

    private List<MarketMoverDto> getUniverseMovers() {
        long now = System.currentTimeMillis();
        CacheEntry c = cache;
        if (c != null && (now - c.timeMs) < 2000) return c.data;

        List<String> symbols = parseUniverseSymbols();
        if (symbols.isEmpty()) return List.of();

        // Resolve symbols -> instrumentKeys using DB
        List<String> keys = resolveInstrumentKeys(symbols);

        // Batch quotes from Upstox (already working for you)
        List<MarketQuoteDto> quotes = marketQuoteService.getQuotes(keys);

        // Convert to movers
        List<MarketMoverDto> out = quotes.stream()
                .filter(q -> q != null && q.getInstrumentKey() != null)
                .filter(q -> q.getLtp() > 0 && q.getPrevClose() > 0) // ignore dead / missing
                .map(this::toMover)
                .collect(Collectors.toList());

        cache = new CacheEntry(now, out);
        return out;
    }

    private MarketMoverDto toMover(MarketQuoteDto q) {
        String key = q.getInstrumentKey();

        // If MarketQuoteDto is not enriched with meta, enrich here
        String sym = q.getTradingSymbol();
        String name = q.getName();
        String ex = q.getExchange();

        if (sym == null || name == null || ex == null) {
            instrumentRepo.findById(key).ifPresent(im -> {
                // We can't mutate record, so re-fetch below in builder style
            });
            Optional<InstrumentMaster> im = instrumentRepo.findById(key);
            if (im.isPresent()) {
                sym = (sym == null) ? im.get().getTradingSymbol() : sym;
                name = (name == null) ? im.get().getName() : name;
                ex = (ex == null) ? im.get().getExchange() : ex;
            }
        }

        return new MarketMoverDto(
                key,
                sym,
                name,
                ex,
                round2(q.getLtp()),
                round2(q.getPrevClose()),
                round2(q.getChange()),
                round2(q.getChangePercent()),
                q.getAsOf()
        );
    }

    private List<String> resolveInstrumentKeys(List<String> symbols) {
        List<String> out = new ArrayList<>();

        for (String s : symbols) {
            String symbol = s.trim();
            if (symbol.isBlank()) continue;

            // Try NSE first, then fallback
            Optional<InstrumentMaster> nse = instrumentRepo.findFirstByTradingSymbolIgnoreCaseAndExchangeIgnoreCase(symbol, "NSE");
            if (nse.isPresent()) {
                out.add(nse.get().getInstrumentKey());
                continue;
            }

            // fallback: use your search() native query (already exists in your repo code)
            List<InstrumentMaster> hits = instrumentRepo.search(symbol, null, 1);
            if (!hits.isEmpty()) {
                out.add(hits.get(0).getInstrumentKey());
            }
        }

        // remove duplicates but keep order
        return out.stream().distinct().toList();
    }

    private List<String> parseUniverseSymbols() {
        if (universeSymbolsCsv == null || universeSymbolsCsv.isBlank()) return List.of();
        return Arrays.stream(universeSymbolsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(200)
                .toList();
    }

    private int normLimit(int limit) {
        if (limit <= 0) return 5;
        return Math.min(limit, 20);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record CacheEntry(long timeMs, List<MarketMoverDto> data) {}
}
