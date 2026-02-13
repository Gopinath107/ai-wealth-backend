package com.djai.wealthadvisor.config; // Verify your package

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.djai.wealthadvisor.dto.ClientWsCommand;
import com.djai.wealthadvisor.service.MarketStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketWsHandler extends TextWebSocketHandler {

    private final MarketStreamService marketStreamService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = (String) session.getAttributes().get("username");
        log.info("✅ WS Opened: {} (User: {})", session.getId(), username);
        marketStreamService.registerSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            log.info("📩 WS Recv: {}", payload);

            // 1. Parse JSON
            ClientWsCommand cmd = objectMapper.readValue(payload, ClientWsCommand.class);

            // 2. Handle Action
            if ("subscribe".equalsIgnoreCase(cmd.getAction())) {
                log.info("🔔 Subscribing session {} to {}", session.getId(), cmd.getInstrumentKeys());
                marketStreamService.subscribe(session.getId(), cmd.getInstrumentKeys(), cmd.getMode());
            } 
            else if ("unsubscribe".equalsIgnoreCase(cmd.getAction())) {
                marketStreamService.unsubscribe(session.getId(), cmd.getInstrumentKeys());
            }

        } catch (Exception e) {
            log.error("❌ WS Error handling message", e);
            // Optional: Send error to client instead of closing
            try {
                session.sendMessage(new TextMessage("{\"type\":\"error\", \"message\":\"" + e.getMessage() + "\"}"));
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("❌ WS Closed: {} ({})", session.getId(), status);
        marketStreamService.unregisterSession(session.getId());
    }
}