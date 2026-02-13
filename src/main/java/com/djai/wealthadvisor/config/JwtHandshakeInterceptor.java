package com.djai.wealthadvisor.config; // Ensure this matches your package structure

import com.djai.wealthadvisor.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus; // Import this
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            // 1. Get URI and Query Params
            URI uri = request.getURI();
            String query = uri.getQuery(); // e.g., "token=eyJhbG..."

            String token = null;
            if (query != null && query.contains("token=")) {
                // Simple parsing for "token=" parameter
                for (String param : query.split("&")) {
                    if (param.startsWith("token=")) {
                        token = param.substring(6);
                        break;
                    }
                }
            }

            // 2. Validate Token
            if (token != null && jwtUtil.validateToken(token)) {
                String username = jwtUtil.extractUsername(token);
                attributes.put("username", username); // Store for use in Handler
                return true; // Proceed to Handshake
            }

            // 3. FAILURE: Token missing or invalid
            log.error("WS Handshake Failed: Invalid or missing token");
            response.setStatusCode(HttpStatus.UNAUTHORIZED); // <--- VITAL FIX: Send 401, not 200
            return false;

        } catch (Exception e) {
            log.error("WS Handshake Error", e);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No action needed
    }
}