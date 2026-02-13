package com.djai.wealthadvisor.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatHistoryDto {
    private Long id;
    private String role; // "user" or "assistant"
    private String content;
    private LocalDateTime timestamp;
}