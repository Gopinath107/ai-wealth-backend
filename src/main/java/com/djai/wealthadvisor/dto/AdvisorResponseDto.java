package com.djai.wealthadvisor.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AdvisorResponseDto {
    private Long sessionId;   // Return the session ID so frontend can continue
    private String chatTitle; // Return title (e.g. "Tata Motors Analysis")
    private String reply;
    private LocalDateTime timestamp;
    private String status;
}