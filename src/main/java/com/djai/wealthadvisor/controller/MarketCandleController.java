package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketCandleService;
import com.djai.wealthadvisor.util.ResponseUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market/candles")
public class MarketCandleController {

    private final MarketCandleService marketCandleService;

    public MarketCandleController(MarketCandleService marketCandleService) {
        this.marketCandleService = marketCandleService;
    }

  
    @GetMapping("/intraday/{instrumentKey}")
    public ResponseEntity<Map<String, Object>> intraday(
            @PathVariable String instrumentKey,
            @RequestParam(defaultValue = "minutes") String unit,
            @RequestParam(defaultValue = "1") int interval,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "asc") String order
    ) {
        try {
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(
                            marketCandleService.getIntradayCandles(instrumentKey, unit, interval, limit, order),
                            true,
                            null
                    )
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    ResponseUtil.buildResponse(null, false, e)
            );
        }
    }

    @GetMapping("/history/{instrumentKey}")
    public ResponseEntity<Map<String, Object>> history(
            @PathVariable String instrumentKey,
            @RequestParam(defaultValue = "minutes") String unit,
            @RequestParam(defaultValue = "1") int interval,
            @RequestParam String from,   // YYYY-MM-DD
            @RequestParam String to,     // YYYY-MM-DD
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "asc") String order
    ) {
        try {
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(
                            marketCandleService.getHistoricalCandles(instrumentKey, unit, interval, from, to, limit, order),
                            true,
                            null
                    )
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    ResponseUtil.buildResponse(null, false, e)
            );
        }
    }

}
