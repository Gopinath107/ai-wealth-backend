package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.dto.AiStrategyDto;
import com.djai.wealthadvisor.service.AiStrategyService;
import com.djai.wealthadvisor.util.ResponseUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiStrategyController {

    private final AiStrategyService aiStrategyService;

    @PostMapping("/strategy")
    public ResponseEntity<Map<String, Object>> strategy(@Valid @RequestBody AiStrategyDto req) {
        try {
            AiStrategyDto result = aiStrategyService.generate(req);
            return ResponseEntity.ok(ResponseUtil.buildResponse(result, true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
