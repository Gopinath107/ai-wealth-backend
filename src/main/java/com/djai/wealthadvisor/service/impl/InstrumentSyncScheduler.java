package com.djai.wealthadvisor.service.impl;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.djai.wealthadvisor.service.InstrumentService;

@Component
public class InstrumentSyncScheduler {

    private final InstrumentService instrumentService;

    public InstrumentSyncScheduler(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    // Upstox files refresh around ~6 AM (IST) and rarely during the day. :contentReference[oaicite:4]{index=4}
    @Scheduled(cron = "${upstox.instruments.cron}", zone = "Asia/Kolkata")
    public void syncDaily() {
        instrumentService.syncNow();
    }
}
