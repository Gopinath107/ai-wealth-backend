package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketMoverService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketMoverController {

    private final MarketMoverService marketMoverService;

    @GetMapping("/top-gainers")
    public ResponseEntity<Map<String, Object>> topGainers(@RequestParam(defaultValue = "5") int limit) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketMoverService.topGainers(limit), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @GetMapping("/top-losers")
    public ResponseEntity<Map<String, Object>> topLosers(@RequestParam(defaultValue = "5") int limit) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    marketMoverService.topLosers(limit), true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
