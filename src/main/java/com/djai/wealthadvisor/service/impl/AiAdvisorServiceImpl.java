package com.djai.wealthadvisor.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.djai.wealthadvisor.dto.AdvisorRequestDto;
import com.djai.wealthadvisor.dto.AdvisorResponseDto;
import com.djai.wealthadvisor.dto.ChatHistoryDto;
import com.djai.wealthadvisor.dto.ChatSessionDto;
import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.entity.AiChatMessage;
import com.djai.wealthadvisor.entity.AiChatSession;
import com.djai.wealthadvisor.entity.User;
import com.djai.wealthadvisor.repository.AiChatMessageRepository;
import com.djai.wealthadvisor.repository.AiChatSessionRepository;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.service.AiPromptService;
import com.djai.wealthadvisor.service.GoalService;
import com.djai.wealthadvisor.service.WatchlistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAdvisorServiceImpl {

    private final UserRepository userRepository;
    private final WatchlistService watchlistService;
    private final GoalService goalService;
    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    @Value("${api.groq.key}")
    private String groqApiKey;

    @Value("${api.groq.url}")
    private String groqUrl;

    @Value("${api.groq.model}")
    private String groqModel;

    private final RestClient restClient = RestClient.create();

    public AdvisorResponseDto processChat(AdvisorRequestDto request) {
        AdvisorResponseDto response = new AdvisorResponseDto();
        response.setTimestamp(LocalDateTime.now());

        try {
            Long userId = request.getUserId();
            Long sessionId = request.getSessionId();
            String text = request.getUserMessage();

            if (!userRepository.existsById(userId)) {
                response.setStatus("ERROR");
                response.setReply("User ID not found: " + userId);
                return response;
            }

            AiChatSession session;
            if (sessionId == null || sessionId <= 0) {
                session = new AiChatSession();
                session.setUserId(userId);
                session.setTitle(generateTitle(text));
                session = sessionRepository.save(session);
            } else {
                session = sessionRepository.findById(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"));

                if (!session.getUserId().equals(userId)) {
                    throw new RuntimeException("Unauthorized session access");
                }
            }

            saveMessage(session, "user", text);

            User user = userRepository.findById(userId).orElseThrow();

            List<WatchlistDto> watchlist;
            try {
                watchlist = watchlistService.list(userId);
            } catch (Exception e) {
                log.warn("Failed to fetch watchlist for user {}", userId);
                watchlist = new ArrayList<>();
            }

            List<GoalDto> goals;
            try {
                goals = goalService.list(userId);
            } catch (Exception e) {
                log.warn("Failed to fetch goals for user {}", userId);
                goals = new ArrayList<>();
            }

            List<AiChatMessage> history = messageRepository.findBySessionId(session.getId(), PageRequest.of(0, 10));
            Collections.reverse(history);

            String systemPrompt = aiPromptService.buildSystemPrompt(user, watchlist, goals);
            Map<String, Object> payload = prepareGroqPayload(systemPrompt, history, text);

            String rawReply = callGroqApi(payload);

            // Validate response — handle empty/null AI replies gracefully
            if (rawReply == null || rawReply.isBlank()) {
                rawReply = "I'm sorry, I couldn't generate a response for that query. Could you please rephrase your question?";
            }

            saveMessage(session, "assistant", rawReply);

            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);

            response.setSessionId(session.getId());
            response.setChatTitle(session.getTitle());
            response.setReply(rawReply);
            response.setStatus("SUCCESS");

        } catch (Exception e) {
            log.error("Chat Error", e);

            response.setStatus("ERROR");
            response.setReply(
                    "I'm having trouble connecting to the AI service right now. Please try again in a moment.");
        }
        return response;
    }

    public List<ChatSessionDto> getUserSessions(Long userId) {
        return sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(s -> {
                    ChatSessionDto dto = new ChatSessionDto();
                    dto.setSessionId(s.getId());
                    dto.setTitle(s.getTitle());
                    dto.setLastActive(s.getUpdatedAt());
                    return dto;
                }).toList();
    }

    public List<ChatHistoryDto> getSessionMessages(Long sessionId) {
        List<AiChatMessage> msgs = messageRepository.findBySessionId(sessionId, PageRequest.of(0, 50));
        List<AiChatMessage> sorted = new ArrayList<>(msgs);
        Collections.reverse(sorted);

        return sorted.stream().map(m -> {
            ChatHistoryDto dto = new ChatHistoryDto();
            dto.setId(m.getId());
            dto.setRole(m.getRole());
            dto.setContent(m.getContent());
            dto.setTimestamp(m.getTimestamp());
            return dto;
        }).toList();
    }

    private String generateTitle(String message) {
        if (message == null)
            return "New Chat";
        String clean = message.trim();
        if (clean.length() > 30)
            return clean.substring(0, 30) + "...";
        return clean;
    }

    private void saveMessage(AiChatSession session, String role, String content) {
        AiChatMessage msg = new AiChatMessage();
        msg.setSession(session);
        msg.setUserId(session.getUserId());
        msg.setRole(role);
        msg.setContent(content);
        messageRepository.save(msg);
    }

    private Map<String, Object> prepareGroqPayload(String systemPrompt, List<AiChatMessage> history,
            String currentMsg) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (AiChatMessage msg : history) {
            if (!msg.getContent().equals(currentMsg)) {
                messages.add(Map.of("role", msg.getRole(), "content", msg.getContent()));
            }
        }
        messages.add(Map.of("role", "user", "content", currentMsg));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", groqModel);
        payload.put("messages", messages);
        payload.put("temperature", 0.7);
        return payload;
    }

    private String callGroqApi(Map<String, Object> payload) {
        try {
            String body = restClient.post().uri(groqUrl)
                    .header("Authorization", "Bearer " + groqApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error("External AI API Error", e);
            throw new RuntimeException("AI Service is unreachable");
        }
    }
}