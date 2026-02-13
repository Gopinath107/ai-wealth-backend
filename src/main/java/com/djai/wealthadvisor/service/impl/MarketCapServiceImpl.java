package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.RapidYahooFinanceClient;
import com.djai.wealthadvisor.dto.MarketCapDto;
import com.djai.wealthadvisor.entity.InstrumentMaster;
import com.djai.wealthadvisor.repository.InstrumentMasterRepository;
import com.djai.wealthadvisor.service.MarketCapService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class MarketCapServiceImpl implements MarketCapService {

    private final InstrumentMasterRepository instrumentRepo;
    private final RapidYahooFinanceClient yahooClient;

    // market cap doesn't change every second. cache 30 minutes.
    private static final long TTL_MS = 30L * 60L * 1000L;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public MarketCapDto getMarketCap(String instrumentKey) {
        Map<String, MarketCapDto> map = getMarketCaps(List.of(instrumentKey));
        return map.get(instrumentKey);
    }

    @Override
    public Map<String, MarketCapDto> getMarketCaps(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return Map.of();

        // 1) Resolve instrumentKey -> yahooSymbol
        Map<String, String> keyToYahoo = new LinkedHashMap<>();
        for (String key : instrumentKeys) {
            if (key == null || key.isBlank()) continue;
            String yahoo = toYahooSymbol(key.trim());
            if (yahoo != null) keyToYahoo.put(key.trim(), yahoo);
        }
        if (keyToYahoo.isEmpty()) return Map.of();

        // 2) Split into cached vs toFetch
        long now = System.currentTimeMillis();
        Map<String, MarketCapDto> out = new LinkedHashMap<>();
        List<String> toFetchYahoo = new ArrayList<>();

        for (Map.Entry<String, String> e : keyToYahoo.entrySet()) {
            String instrumentKey = e.getKey();
            String yahooSymbol = e.getValue();

            CacheEntry ce = cache.get(yahooSymbol);
            if (ce != null && (now - ce.timeMs) < TTL_MS) {
                out.put(instrumentKey, ce.dto);
            } else {
                toFetchYahoo.add(yahooSymbol);
            }
        }

        // 3) Fetch missing in one batch call
        if (!toFetchYahoo.isEmpty()) {
            Map<String, RapidYahooFinanceClient.QuoteMini> fetched = yahooClient.getQuotesMarketCap(toFetchYahoo, "IN");
            LocalDateTime asOf = LocalDateTime.now();

            for (String yahooSym : toFetchYahoo) {
                RapidYahooFinanceClient.QuoteMini q = fetched.get(yahooSym);

                MarketCapDto dto = new MarketCapDto(
                        null, // we'll assign per instrumentKey below
                        yahooSym,
                        q != null ? q.marketCap() : null,
                        q != null ? q.currency() : null,
                        asOf
                );

                cache.put(yahooSym, new CacheEntry(System.currentTimeMillis(), dto));
            }

            // attach back to instrument keys
            for (Map.Entry<String, String> e : keyToYahoo.entrySet()) {
                String instrumentKey = e.getKey();
                String yahooSymbol = e.getValue();
                CacheEntry ce = cache.get(yahooSymbol);
                if (ce != null) {
                    MarketCapDto dto = new MarketCapDto(
                            instrumentKey,
                            ce.dto.yahooSymbol(),
                            ce.dto.marketCap(),
                            ce.dto.currency(),
                            ce.dto.asOf()
                    );
                    out.put(instrumentKey, dto);
                }
            }
        }

        return out;
    }

    private String toYahooSymbol(String instrumentKey) {
        // We rely on instrument_master for tradingSymbol & exchange
        Optional<InstrumentMaster> imOpt = instrumentRepo.findById(instrumentKey);
        if (imOpt.isEmpty()) return null;

        InstrumentMaster im = imOpt.get();
        String ts = im.getTradingSymbol();
        String ex = im.getExchange();

        if (ts == null || ts.isBlank()) return null;

        // Indices: skip market cap (not meaningful)
        if (im.getSegment() != null && im.getSegment().contains("INDEX")) return null;

        // NSE -> .NS ; BSE -> .BO
        if (ex != null) {
            if (ex.equalsIgnoreCase("NSE")) return ts + ".NS";
            if (ex.equalsIgnoreCase("BSE")) return ts + ".BO";
        }

        // fallback: infer from key prefix
        if (instrumentKey.startsWith("NSE_")) return ts + ".NS";
        if (instrumentKey.startsWith("BSE_")) return ts + ".BO";

        return ts;
    }

    private record CacheEntry(long timeMs, MarketCapDto dto) {}
}
