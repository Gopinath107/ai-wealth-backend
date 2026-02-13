package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.client.UpstoxClient;
import com.djai.wealthadvisor.dto.LivePriceDto;
import com.djai.wealthadvisor.service.LivePriceStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class LivePriceStreamServiceImpl implements LivePriceStreamService {

    private final UpstoxClient upstoxClient;

    // shared scheduler for all SSE connections
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public LivePriceStreamServiceImpl(UpstoxClient upstoxClient) {
        this.upstoxClient = upstoxClient;
    }

    @Override
    public SseEmitter streamLtp(String instrumentKey, long freqMs) {
        // sanity
        if (freqMs < 300) freqMs = 300; // don’t go crazy
        if (freqMs > 5000) freqMs = 5000;

        // no timeout (or set a long one)
        SseEmitter emitter = new SseEmitter(0L);

        final String key = instrumentKey;

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                Map<String, UpstoxClient.QuoteSnapshot> snap =
                        upstoxClient.getLtpSnapshots(List.of(key));

                UpstoxClient.QuoteSnapshot s = snap.get(key);

                double ltp = (s == null) ? 0 : s.ltp();
                double prev = (s == null) ? 0 : s.prevClose();
                double change = ltp - prev;
                double pct = (prev == 0) ? 0 : (change / prev) * 100.0;

                LivePriceDto dto = new LivePriceDto(
                        key,
                        round2(ltp),
                        round2(change),
                        round2(pct),
                        LocalDateTime.now()
                );

                emitter.send(SseEmitter.event()
                        .name("ltp")
                        .data(dto));

            } catch (IOException io) {
                // client disconnected
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(e.getMessage()));
                } catch (Exception ignore) {}
            }
        }, 0, freqMs, TimeUnit.MILLISECONDS);

        // cleanup on close
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> {
            future.cancel(true);
            emitter.complete();
        });
        emitter.onError(err -> {
            future.cancel(true);
            emitter.completeWithError(err);
        });

        return emitter;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
