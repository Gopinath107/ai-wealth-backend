package com.djai.wealthadvisor.dto;
import lombok.Data;

@Data
public class AdvisorRequestDto {
    private Long userId;
    private String userMessage;
    private Long sessionId; // Optional: If null, start NEW chat
}