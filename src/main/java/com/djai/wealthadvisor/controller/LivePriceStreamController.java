package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.LivePriceStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/market/stream")
public class LivePriceStreamController {

    private final LivePriceStreamService livePriceStreamService;

    public LivePriceStreamController(LivePriceStreamService livePriceStreamService) {
        this.livePriceStreamService = livePriceStreamService;
    }

    @GetMapping(value = "/ltp/{instrumentKey}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLtp(
            @PathVariable String instrumentKey,
            @RequestParam(defaultValue = "1000") long freqMs
    ) {
        return livePriceStreamService.streamLtp(instrumentKey, freqMs);
    }
}
