package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.dto.AdvisorRequestDto;
import com.djai.wealthadvisor.dto.AdvisorResponseDto;
import com.djai.wealthadvisor.dto.ChatHistoryDto;
import com.djai.wealthadvisor.dto.ChatSessionDto;
import com.djai.wealthadvisor.service.impl.AiAdvisorServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAdvisorController {

    private final AiAdvisorServiceImpl aiAdvisorService;

    @PostMapping("/chat")
    public ResponseEntity<AdvisorResponseDto> chat(@RequestBody AdvisorRequestDto request) {
        return ResponseEntity.ok(aiAdvisorService.processChat(request));
    }

    @GetMapping("/sessions/{userId}")
    public ResponseEntity<List<ChatSessionDto>> getSessions(@PathVariable Long userId) {
        return ResponseEntity.ok(aiAdvisorService.getUserSessions(userId));
    }

    @GetMapping("/session/{sessionId}/messages")
    public ResponseEntity<List<ChatHistoryDto>> getSessionMessages(@PathVariable Long sessionId) {
        return ResponseEntity.ok(aiAdvisorService.getSessionMessages(sessionId));
    }
}