package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketIndexService;
import com.djai.wealthadvisor.util.ResponseUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketIndexController {

    private final MarketIndexService marketIndexService;

    public MarketIndexController(MarketIndexService marketIndexService) {
        this.marketIndexService = marketIndexService;
    }

    @GetMapping("/indexes")
    public ResponseEntity<Map<String, Object>> getIndexes() {
        try {
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(marketIndexService.getLiveIndexes(), true, null)
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    ResponseUtil.buildResponse(null, false, e)
            );
        }
    }
}
