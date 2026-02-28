package com.djai.wealthadvisor.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdvisorResponseDto {
    private Long sessionId; // Return the session ID so frontend can continue
    private String chatTitle; // Return title (e.g. "Tata Motors Analysis")
    private String reply;
    private List<String> followUps; // Suggested follow-up questions
    private LocalDateTime timestamp;
    private String status;
}
