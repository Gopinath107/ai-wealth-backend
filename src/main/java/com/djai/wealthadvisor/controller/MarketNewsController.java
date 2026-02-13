package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.MarketNewsService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class MarketNewsController {

    private final MarketNewsService newsService;

    @GetMapping("/feed")
    public ResponseEntity<Map<String, Object>> feed(
            @RequestParam(defaultValue = "all") String tab,
            @RequestParam(defaultValue = "20") Integer limit,
            @RequestParam(defaultValue = "1") Integer page
    ) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(newsService.feed(tab, limit, page), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(newsService.search(q, limit), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
