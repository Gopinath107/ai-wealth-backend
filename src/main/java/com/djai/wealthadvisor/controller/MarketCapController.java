package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketCapService;
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
public class MarketCapController {

    private final MarketCapService marketCapService;

    @GetMapping("/market-cap/{instrumentKey}")
    public ResponseEntity<Map<String, Object>> marketCap(@PathVariable String instrumentKey) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketCapService.getMarketCap(instrumentKey), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @GetMapping("/market-caps")
    public ResponseEntity<Map<String, Object>> marketCaps(@RequestParam("keys") String keysCsv) {
        try {
            List<String> keys = Arrays.stream(keysCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();

            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketCapService.getMarketCaps(keys), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
