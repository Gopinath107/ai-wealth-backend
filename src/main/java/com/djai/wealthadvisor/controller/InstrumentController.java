package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.InstrumentService;
import com.djai.wealthadvisor.util.ResponseUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    // 🔎 Search for dropdown suggestions
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String exchange
    ) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    instrumentService.search(q, limit, exchange),
                    true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    // 🎯 Resolve user input to ONE instrumentKey (frontend can call this before candles)
    @GetMapping("/resolve")
    public ResponseEntity<Map<String, Object>> resolve(
            @RequestParam String q,
            @RequestParam(required = false) String exchange
    ) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    instrumentService.resolve(q, exchange),
                    true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    // (Optional) manual sync button for admin/testing
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncNow() {
        try {
            int count = instrumentService.syncNow();
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    Map.of("upserted", count),
                    true, null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
