package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketFeedService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketFeedController {

    private final MarketFeedService marketFeedService;

    @GetMapping("/feed")
    public ResponseEntity<Map<String, Object>> feed(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeMarketCap   // ✅ NEW
    ) {
        try {
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(
                            marketFeedService.getFeed(limit, q, includeMarketCap),
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
