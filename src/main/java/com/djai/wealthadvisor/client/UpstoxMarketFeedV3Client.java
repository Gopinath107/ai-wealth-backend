package com.djai.wealthadvisor.client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upstox.marketdatafeed.feeder.rpc.MarketDataFeed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpstoxMarketFeedV3Client {

    private final UpstoxAuthorizeClient authorizeClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final OkHttpClient okHttp = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();

    private volatile WebSocket ws;
    private final AtomicBoolean isConnected = new AtomicBoolean(false); // 🔥 FIX: Use AtomicBoolean for thread safety
    private volatile Consumer<MarketDataFeed.FeedResponse> onFeed;
    private final Map<String, String> subscriptions = new ConcurrentHashMap<>();

    public void setOnFeed(Consumer<MarketDataFeed.FeedResponse> onFeed) {
        log.info("🎯 onFeed callback registered: {}", (onFeed != null ? "YES" : "NO"));
        this.onFeed = onFeed;
    }

    public synchronized void subscribe(List<String> instrumentKeys, String mode) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.warn("⚠️ Subscribe called with empty keys");
            return;
        }
        
        String m = normalizeMode(mode);
        log.info("📥 Subscribe request: Mode={} Keys={}", m, instrumentKeys);

        // Store subscriptions first
        for (String k : instrumentKeys) {
            if (k != null && !k.isBlank()) {
                String oldMode = subscriptions.get(k);
                String newMode = pickStrongerMode(oldMode, m);
                subscriptions.put(k, newMode);
                log.debug("   - Key: {} | OldMode: {} | NewMode: {}", k, oldMode, newMode);
            }
        }
        
        ensureConnected();
        
        // 🔥 FIX: Only send if already connected, otherwise onOpen will handle it
        if (isConnected.get()) {
            log.info("✅ Already connected, sending subscription immediately");
            sendSubForMode(m, instrumentKeys);
        } else {
            log.info("⏳ Subscription queued (connection pending): Mode={} Keys={}", m, instrumentKeys);
        }
    }

    public synchronized void unsubscribe(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return;
        
        log.info("📤 Unsubscribe request: Keys={}", instrumentKeys);
        
        for (String k : instrumentKeys) {
            if (k != null) subscriptions.remove(k);
        }
        
        if (ws != null && isConnected.get()) {
            sendUnsub(instrumentKeys);
        }
    }

    private synchronized void ensureConnected() {
        if (ws != null && isConnected.get()) {
            log.debug("✅ Already connected to Upstox");
            return;
        }

        if (ws != null) {
            log.warn("⚠️ WebSocket exists but not connected, resetting...");
            reset();
        }

        try {
            String wssUrl = authorizeClient.getAuthorizedRedirectUri(); 
            log.info("🔌 Connecting to Upstox WebSocket: {}", wssUrl);

            Request request = new Request.Builder().url(wssUrl).build();

            ws = okHttp.newWebSocket(request, new WebSocketListener() {

                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    isConnected.set(true); // 🔥 FIX: Thread-safe update
                    log.info("✅✅✅ Upstox WS Connected Successfully! ✅✅✅");
                    log.info("📊 Resubscribing to {} pending keys...", subscriptions.size());
                    
                    // Group by mode and resend ALL pending subscriptions
                    Map<String, List<String>> byMode = new HashMap<>();
                    subscriptions.forEach((k, m) -> {
                        byMode.computeIfAbsent(m, x -> new ArrayList<>()).add(k);
                    });
                    
                    log.info("📋 Subscriptions by mode: {}", byMode);
                    byMode.forEach((mode, keys) -> {
                        log.info("📤 Resubscribing Mode={} Keys={}", mode, keys);
                        sendSubForMode(mode, keys);
                    });
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    // Binary Message = DATA (THIS IS WHAT WE EXPECT FROM UPSTOX)
                    try {
                        log.info("📡 RAW BINARY MESSAGE RECEIVED: {} bytes", bytes.size());
                        
                        MarketDataFeed.FeedResponse feed = MarketDataFeed.FeedResponse.parseFrom(bytes.toByteArray());
                        log.info("📊 PARSED FEED: Type={} | Instruments={} | FeedsMapSize={}", 
                                feed.getType(), 
                                feed.getFeedsMap().keySet(), 
                                feed.getFeedsMap().size());
                        
                        if (onFeed != null) {
                            log.info("🚀 Calling onFeed callback...");
                            onFeed.accept(feed);
                        } else {
                            log.error("❌❌❌ CRITICAL: Feed received but onFeed callback is NULL! ❌❌❌");
                        }
                    } catch (Exception e) {
                        log.error("❌ Protobuf parse failed", e);
                    }
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    // Text Message = USUALLY ERROR, INFO, or ACKNOWLEDGMENT
                    log.info("📨 UPSTOX TEXT MESSAGE: {}", text);
                    
                    // 🔥 NEW: Parse acknowledgment messages
                    try {
                        Map<String, Object> msg = mapper.readValue(text, Map.class);
                        String status = (String) msg.get("status");
                        String method = (String) msg.get("method");
                        
                        if ("success".equals(status)) {
                            log.info("✅ Upstox acknowledged: method={}", method);
                        } else if ("error".equals(status)) {
                            log.error("❌ Upstox error: {}", msg);
                        }
                    } catch (Exception e) {
                        log.debug("Could not parse text message as JSON: {}", text);
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    log.error("❌❌❌ Upstox Connection FAILED ❌❌❌", t);
                    if (response != null) {
                        log.error("Response: {} {}", response.code(), response.message());
                    }
                    reset();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    log.warn("⚠️ Upstox WS Closed: code={} reason={}", code, reason);
                    reset();
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    log.warn("⚠️ Upstox WS Closing: code={} reason={}", code, reason);
                }
            });
            
            log.info("🔄 WebSocket connection initiated, waiting for onOpen...");
            
        } catch (Exception e) {
            log.error("❌ Failed to create WebSocket connection", e);
            reset();
        }
    }

    private synchronized void reset() {
        log.warn("🔄 Resetting WebSocket connection state");
        ws = null;
        isConnected.set(false);
    }

    private void sendSubForMode(String mode, List<String> keys) {
        if (ws == null) {
            log.error("❌ Cannot send subscription - WebSocket is NULL");
            return;
        }
        
        if (!isConnected.get()) {
            log.error("❌ Cannot send subscription - Not connected (isConnected=false)");
            return;
        }
        
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("guid", UUID.randomUUID().toString().replace("-", ""));
            msg.put("method", "sub");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("mode", mode);
            data.put("instrumentKeys", keys);
            msg.put("data", data);

            byte[] jsonBytes = mapper.writeValueAsBytes(msg);
            String jsonStr = new String(jsonBytes);
            
            log.info("📤📤📤 SENDING SUBSCRIPTION TO UPSTOX 📤📤📤");
            log.info("Message: {}", jsonStr);
            log.info("Mode: {} | Keys: {}", mode, keys);
            
            boolean sent = ws.send(ByteString.of(jsonBytes));
            
            if (sent) {
                log.info("✅ Subscription sent successfully");
            } else {
                log.error("❌ Failed to send subscription (ws.send returned false)");
            }

        } catch (Exception e) {
            log.error("❌ Failed to send subscription", e);
        }
    }

    private void sendUnsub(List<String> keys) {
        if (ws == null || !isConnected.get()) {
            log.warn("⚠️ Cannot send unsubscription - not connected");
            return;
        }
        
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("guid", UUID.randomUUID().toString().replace("-", ""));
            msg.put("method", "unsub");
            msg.put("data", Map.of("instrumentKeys", keys));
            
            String jsonStr = mapper.writeValueAsString(msg);
            log.info("📤 Sending Unsubscription: {}", jsonStr);
            
            ws.send(ByteString.of(mapper.writeValueAsBytes(msg)));
        } catch (Exception e) {
            log.error("❌ Failed to send unsubscribe", e);
        }
    }

    private String normalizeMode(String mode) {
        if (mode == null) return "full";
        return switch (mode.trim().toLowerCase()) {
            case "ltpc" -> "ltpc";
            case "full", "full_d5" -> "full";
            case "option_greeks" -> "option_greeks";
            default -> "full";
        };
    }

    private String pickStrongerMode(String existing, String incoming) {
        if (existing == null) return incoming;
        List<String> order = List.of("ltpc", "option_greeks", "full");
        int existingIdx = order.indexOf(existing);
        int incomingIdx = order.indexOf(incoming);
        return incomingIdx > existingIdx ? incoming : existing;
    }
    
    // 🔥 Public methods for debugging
    public boolean isConnected() {
        return isConnected.get();
    }
    
    public Set<String> getSubscribedKeys() {
        return new HashSet<>(subscriptions.keySet());
    }
    
    public boolean hasOnFeedCallback() {
        return onFeed != null;
    }
}