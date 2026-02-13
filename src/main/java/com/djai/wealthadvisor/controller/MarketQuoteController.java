package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketQuoteService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketQuoteController {

    private final MarketQuoteService marketQuoteService;

    // Single quote (instrumentKey contains | so use encoded path in frontend)
    @GetMapping("/quote/{instrumentKey}")
    public ResponseEntity<Map<String, Object>> quote(@PathVariable String instrumentKey) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketQuoteService.getQuote(instrumentKey), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    // Batch quote: /api/market/quotes?keys=K1,K2,K3
    @GetMapping("/quotes")
    public ResponseEntity<Map<String, Object>> quotes(@RequestParam("keys") String keysCsv) {
        try {
            List<String> keys = Arrays.stream(keysCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();

            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketQuoteService.getQuotes(keys), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
