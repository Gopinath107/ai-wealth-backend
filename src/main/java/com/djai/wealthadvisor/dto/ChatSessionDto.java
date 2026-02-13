package com.djai.wealthadvisor.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChatSessionDto {
    private Long sessionId;
    private String title;
    private LocalDateTime lastActive;
}