package com.djai.wealthadvisor.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.djai.wealthadvisor.client.UpstoxMarketFeedV3Client;
import com.djai.wealthadvisor.dto.ServerMarketEvent;
import com.djai.wealthadvisor.service.MarketStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upstox.marketdatafeed.feeder.rpc.MarketDataFeed;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketStreamServiceImpl implements MarketStreamService {

    private final UpstoxMarketFeedV3Client upstoxClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionToKeys = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> keyToSessions = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        log.info("🎬 MarketStreamServiceImpl initializing...");
        log.info("🔗 Registering Upstox feed callback...");
        upstoxClient.setOnFeed(this::handleUpstoxFeed);
        
        // 🔥 VERIFY callback was registered
        if (upstoxClient.hasOnFeedCallback()) {
            log.info("✅ Upstox callback successfully registered!");
        } else {
            log.error("❌❌❌ CRITICAL: Upstox callback registration FAILED! ❌❌❌");
        }
    }

    @Override
    public void registerSession(WebSocketSession session) {
        sessions.put(session.getId(), session);
        sessionToKeys.put(session.getId(), ConcurrentHashMap.newKeySet());
        log.info("✅ WS Client Connected: {} | Total sessions: {}", session.getId(), sessions.size());
    }

    @Override
    public void unregisterSession(String sessionId) {
        Set<String> keys = sessionToKeys.remove(sessionId);

        if (keys != null) {
            log.info("🔍 Session {} had {} subscriptions", sessionId, keys.size());
            List<String> keysToUnsubscribe = new ArrayList<>();

            for (String key : keys) {
                Set<String> subs = keyToSessions.get(key);
                if (subs != null) {
                    subs.remove(sessionId);

                    // If NO ONE is listening to this key anymore, remove it completely
                    if (subs.isEmpty()) {
                        keyToSessions.remove(key);
                        keysToUnsubscribe.add(key);
                    }
                }
            }

            // 🛑 Tell Upstox to stop sending these keys
            if (!keysToUnsubscribe.isEmpty()) {
                log.info("🚫 Unsubscribing from Upstox (No listeners): {}", keysToUnsubscribe);
                upstoxClient.unsubscribe(keysToUnsubscribe);
            }
        }

        sessions.remove(sessionId);
        log.info("❌ WS Client Disconnected: {} | Remaining sessions: {}", sessionId, sessions.size());
    }

    @Override
    public void subscribe(String sessionId, List<String> instrumentKeys, String mode) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.warn("⚠️ Subscribe called with empty keys for session {}", sessionId);
            return;
        }

        log.info("🔔 Session {} subscribing to {} keys with mode: {}", sessionId, instrumentKeys.size(), mode);
        log.debug("   Keys: {}", instrumentKeys);

        for (String key : instrumentKeys) {
            if (key == null || key.isBlank()) continue;
            
            // Track this session's interest
            sessionToKeys.computeIfAbsent(sessionId, x -> ConcurrentHashMap.newKeySet()).add(key);
            
            // Track which sessions are interested in this key
            keyToSessions.computeIfAbsent(key, x -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
        
        log.info("📊 Current state: {} total sessions, {} tracked keys", sessions.size(), keyToSessions.size());
        
        // Forward to Upstox
        upstoxClient.subscribe(instrumentKeys, mode == null ? "full" : mode);
    }

    @Override
    public void unsubscribe(String sessionId, List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return;

        Set<String> sKeys = sessionToKeys.get(sessionId);
        if (sKeys == null) {
            log.warn("⚠️ Session {} not found in sessionToKeys", sessionId);
            return;
        }

        List<String> keysToUnsubscribe = new ArrayList<>();

        for (String key : instrumentKeys) {
            if (key == null || key.isBlank()) continue;
            
            // Remove from this session's list
            sKeys.remove(key);

            // Check if anyone else is listening
            Set<String> subs = keyToSessions.get(key);
            if (subs != null) {
                subs.remove(sessionId);
                if (subs.isEmpty()) {
                    keyToSessions.remove(key);
                    keysToUnsubscribe.add(key);
                }
            }
        }

        if (!keysToUnsubscribe.isEmpty()) {
            log.info("🚫 Unsubscribing from Upstox (User requested): {}", keysToUnsubscribe);
            upstoxClient.unsubscribe(keysToUnsubscribe);
        }
    }

    private void handleUpstoxFeed(MarketDataFeed.FeedResponse feed) {
        if (feed == null) {
            log.warn("⚠️ Received null feed from Upstox");
            return;
        }

        log.info("🔥🔥🔥 INCOMING FEED 🔥🔥🔥");
        log.info("   Type: {}", feed.getType());
        log.info("   Instruments: {}", feed.getFeedsMap().keySet());
        log.info("   Feed count: {}", feed.getFeedsMap().size());

        feed.getFeedsMap().forEach((instrumentKey, f) -> {
            
            Set<String> targetSessionIds = keyToSessions.get(instrumentKey);
            
            if (targetSessionIds == null || targetSessionIds.isEmpty()) {
                log.debug("📭 No subscribers for key: {}", instrumentKey);
                return; 
            }

            log.info("📬 Processing feed for key: {} | Subscribers: {}", instrumentKey, targetSessionIds.size());

            Double ltp = null;
            String ltt = null;

            // Extract LTP from various feed types
            if (f.hasLtpc()) {
                ltp = f.getLtpc().getLtp();
                ltt = String.valueOf(f.getLtpc().getLtt());
                log.debug("   LTPC Feed: LTP={} LTT={}", ltp, ltt);
            } 
            else if (f.hasFullFeed()) {
                MarketDataFeed.FullFeed ff = f.getFullFeed();
                if (ff.hasMarketFF() && ff.getMarketFF().hasLtpc()) {
                    ltp = ff.getMarketFF().getLtpc().getLtp();
                    ltt = String.valueOf(ff.getMarketFF().getLtpc().getLtt());
                    log.debug("   Full Market Feed: LTP={} LTT={}", ltp, ltt);
                } else if (ff.hasIndexFF() && ff.getIndexFF().hasLtpc()) {
                    ltp = ff.getIndexFF().getLtpc().getLtp();
                    ltt = String.valueOf(ff.getIndexFF().getLtpc().getLtt());
                    log.debug("   Full Index Feed: LTP={} LTT={}", ltp, ltt);
                }
            }

            if (ltp != null) {
                ServerMarketEvent ev = ServerMarketEvent.builder()
                        .type("tick")
                        .instrumentKey(instrumentKey)
                        .ltp(ltp)
                        .ltt(ltt)
                        .build();

                log.info("🚀 Broadcasting to {} sessions: LTP={}", targetSessionIds.size(), ltp);
                broadcastToSessions(targetSessionIds, ev);
            } else {
                log.warn("⚠️ Could not extract LTP from feed for key: {}", instrumentKey);
            }
        });
    }

    private void broadcastToSessions(Set<String> sessionIds, ServerMarketEvent ev) {
        try {
            String json = mapper.writeValueAsString(ev);
            TextMessage msg = new TextMessage(json);

            int successCount = 0;
            int failCount = 0;

            for (String id : sessionIds) {
                WebSocketSession s = sessions.get(id);
                if (s == null || !s.isOpen()) {
                    log.warn("⚠️ Session {} is null or closed, skipping", id);
                    failCount++;
                    continue;
                }

                try {
                    synchronized (s) {
                        s.sendMessage(msg);
                        successCount++;
                        log.debug("✅ Sent to session {}: {}", id, json);
                    }
                } catch (Exception e) {
                    log.error("❌ Failed to send to session {}", id, e);
                    failCount++;
                }
            }

            log.info("📊 Broadcast complete: {} success, {} failed | Message: {}", 
                    successCount, failCount, json);

        } catch (Exception e) {
            log.error("❌ WS Broadcast Failed during JSON serialization", e);
        }
    }
}