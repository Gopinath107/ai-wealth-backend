package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.UpstoxHistoryClient;
import com.djai.wealthadvisor.dto.CandleDto;
import com.djai.wealthadvisor.dto.CandleSeriesDto;
import com.djai.wealthadvisor.service.MarketCandleService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class MarketCandleServiceImpl implements MarketCandleService {

    private final UpstoxHistoryClient upstoxHistoryClient;

    // small 2s cache (optional)
    private volatile CacheEntry cache;

    public MarketCandleServiceImpl(UpstoxHistoryClient upstoxHistoryClient) {
        this.upstoxHistoryClient = upstoxHistoryClient;
    }
    
    @Override
    public CandleSeriesDto getHistoricalCandles(String instrumentKey, String unit, int interval,
                                               String fromDate, String toDate, int limit, String order) {

        unit = (unit == null || unit.isBlank()) ? "minutes" : unit.trim().toLowerCase();
        interval = (interval <= 0) ? 1 : interval;
        limit = (limit <= 0) ? 200 : Math.min(limit, 500);
        order = (order == null || order.isBlank()) ? "asc" : order.trim().toLowerCase();

        // Upstox expects to_date then from_date in URL. We'll pass as (toDate, fromDate)
        var candles = upstoxHistoryClient.getHistoricalCandles(instrumentKey, unit, interval, toDate, fromDate);

        if ("asc".equals(order)) {
            java.util.Collections.reverse(candles);
        }

        if (candles.size() > limit) {
            candles = candles.subList(candles.size() - limit, candles.size());
        }

        return new com.djai.wealthadvisor.dto.CandleSeriesDto(
                instrumentKey,
                unit,
                interval,
                order,
                limit,
                java.time.LocalDateTime.now(),
                candles
        );
    }


    @Override
    public CandleSeriesDto getIntradayCandles(String instrumentKey, String unit, int interval, int limit, String order) {

        unit = (unit == null || unit.isBlank()) ? "minutes" : unit.trim().toLowerCase();
        interval = (interval <= 0) ? 1 : interval;
        limit = (limit <= 0) ? 200 : Math.min(limit, 500);
        order = (order == null || order.isBlank()) ? "asc" : order.trim().toLowerCase();

        // cache key
        String key = instrumentKey + "|" + unit + "|" + interval + "|" + limit + "|" + order;

        CacheEntry c = cache;
        long now = System.currentTimeMillis();
        if (c != null && c.key.equals(key) && (now - c.timeMs) < 2000) {
            return c.data;
        }

        List<CandleDto> candles = upstoxHistoryClient.getIntradayCandles(instrumentKey, unit, interval);

        // Upstox often returns latest-first (desc). Your chart usually wants asc.
        if ("asc".equals(order)) {
            Collections.reverse(candles);
        }

        // apply limit AFTER ordering
        if (candles.size() > limit) {
            candles = candles.subList(candles.size() - limit, candles.size());
        }

        CandleSeriesDto dto = new CandleSeriesDto(
                instrumentKey,
                unit,
                interval,
                order,
                limit,
                LocalDateTime.now(),
                candles
        );

        cache = new CacheEntry(key, now, dto);
        return dto;
    }

    private record CacheEntry(String key, long timeMs, CandleSeriesDto data) {}
}
