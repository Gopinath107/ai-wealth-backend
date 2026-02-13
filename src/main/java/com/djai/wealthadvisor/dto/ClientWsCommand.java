package com.djai.wealthadvisor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // Prevents crash on extra fields
public class ClientWsCommand {
    private String action;          // "subscribe" or "unsubscribe"
    private String mode;            // "full" or "ltpc"
    private List<String> instrumentKeys;
}