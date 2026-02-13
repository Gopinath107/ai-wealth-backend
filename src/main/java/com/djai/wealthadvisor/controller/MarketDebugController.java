package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.client.UpstoxMarketFeedV3Client;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * Diagnostic endpoint to check WebSocket and Upstox connection status
 */
@RestController
@RequestMapping("/api/market/debug")
@RequiredArgsConstructor
public class MarketDebugController {

    private final UpstoxMarketFeedV3Client upstoxClient;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        
        status.put("upstoxConnected", upstoxClient.isConnected());
        status.put("hasOnFeedCallback", upstoxClient.hasOnFeedCallback());
        status.put("subscribedKeys", upstoxClient.getSubscribedKeys());
        status.put("subscribedCount", upstoxClient.getSubscribedKeys().size());
        status.put("timestamp", new Date());
        
        return ResponseEntity.ok(status);
    }
}