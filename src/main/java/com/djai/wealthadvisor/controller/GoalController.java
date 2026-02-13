package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.dto.GoalDto;
import com.djai.wealthadvisor.service.GoalService;
import com.djai.wealthadvisor.service.GoalAiService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;
    private final GoalAiService goalAiService;

    /**
     * POST /api/goals/ai-plan
     * Multi-turn AI chatbot for goal planning.
     * Body: { "userId": 1, "messages": [{"role":"user","content":"I want to buy a
     * car"}, ...] }
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/ai-plan")
    public ResponseEntity<Map<String, Object>> generateAiPlan(@RequestBody Map<String, Object> payload) {
        try {
            // Extract userId
            Long userId = null;
            if (payload.containsKey("userId")) {
                userId = Long.valueOf(payload.get("userId").toString());
            }

            // Multi-turn: messages[] array
            List<Map<String, String>> messages = (List<Map<String, String>>) payload.get("messages");
            if (messages == null || messages.isEmpty())
                throw new IllegalArgumentException("Messages array is required");

            GoalDto result = goalAiService.generatePlanFromMessages(messages, userId);
            return ResponseEntity.ok(ResponseUtil.buildResponse(result, true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    /**
     * GET /api/goals?userId=X
     * List all goals for a user.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Long userId) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(goalService.list(userId), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    /**
     * POST /api/goals
     * Create a new goal (works for both manual entry and AI-generated plans).
     * Body: GoalDto JSON (must include userId).
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody GoalDto req) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(goalService.add(req), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    /**
     * PUT /api/goals/{goalKey}
     * Update an existing goal (edit fields, add contributions, etc.).
     * Body: GoalDto JSON (must include userId).
     */
    @PutMapping("/{goalKey}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String goalKey, @RequestBody GoalDto req) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(goalService.update(goalKey, req), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    /**
     * DELETE /api/goals/{goalKey}?userId=X
     * Remove a goal.
     */
    @DeleteMapping("/{goalKey}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String goalKey, @RequestParam Long userId) {
        try {
            goalService.delete(goalKey, userId);
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    Map.of("deleted", true, "goalKey", goalKey, "userId", userId),
                    true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
