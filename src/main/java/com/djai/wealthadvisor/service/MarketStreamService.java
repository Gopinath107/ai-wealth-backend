package com.djai.wealthadvisor.service;

import org.springframework.web.socket.WebSocketSession;
import java.util.List;

public interface MarketStreamService {
    void registerSession(WebSocketSession session);
    void unregisterSession(String sessionId);

    void subscribe(String sessionId, List<String> instrumentKeys, String mode);
    void unsubscribe(String sessionId, List<String> instrumentKeys);
}
