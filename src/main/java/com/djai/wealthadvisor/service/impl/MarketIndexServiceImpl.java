package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.UpstoxClient;
import com.djai.wealthadvisor.dto.MarketIndexDto;
import com.djai.wealthadvisor.service.MarketIndexService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class MarketIndexServiceImpl implements MarketIndexService {

    private final UpstoxClient upstoxClient;

    @Value("${upstox.index.nifty50}")
    private String nifty50Key;

    @Value("${upstox.index.niftyBank}")
    private String niftyBankKey;

    @Value("${upstox.index.sensex}")
    private String sensexKey;

    // small 2s cache (so UI can poll without hammering Upstox)
    private volatile CacheEntry cache;

    public MarketIndexServiceImpl(UpstoxClient upstoxClient) {
        this.upstoxClient = upstoxClient;
    }

    @Override
    public List<MarketIndexDto> getLiveIndexes() {
        CacheEntry c = cache;
        long now = System.currentTimeMillis();

        if (c != null && (now - c.timestampMs) < 2000) {
            return c.data;
        }

        List<String> keys = List.of(nifty50Key, niftyBankKey, sensexKey);
        Map<String, UpstoxClient.QuoteSnapshot> snaps = upstoxClient.getLtpSnapshots(keys);

        LocalDateTime asOf = LocalDateTime.now();

        List<MarketIndexDto> out = new ArrayList<>();
        out.add(toDto("NIFTY 50", snaps.get(nifty50Key), asOf));
        out.add(toDto("NIFTY BANK", snaps.get(niftyBankKey), asOf));
        out.add(toDto("SENSEX", snaps.get(sensexKey), asOf));

        cache = new CacheEntry(now, out);
        return out;
    }

    private MarketIndexDto toDto(String name, UpstoxClient.QuoteSnapshot s, LocalDateTime asOf) {
        double ltp = (s == null) ? 0 : s.ltp();
        double prev = (s == null) ? 0 : s.prevClose();

        double change = ltp - prev;
        double pct = (prev == 0) ? 0 : (change / prev) * 100.0;

        return new MarketIndexDto(name, round2(ltp), round2(change), round2(pct), asOf);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record CacheEntry(long timestampMs, List<MarketIndexDto> data) {}
}
