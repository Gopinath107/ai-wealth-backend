package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.dto.WatchlistDto;
import com.djai.wealthadvisor.service.WatchlistService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    // Add: payload includes userId + query
    @PostMapping
    public ResponseEntity<Map<String, Object>> add(@RequestBody WatchlistDto req) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(watchlistService.add(req), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    // List: userId must be passed (payload = request param)
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam Long userId) {
        try {
            return ResponseEntity.ok(ResponseUtil.buildResponse(watchlistService.list(userId), true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    // Delete: userId must be passed (payload = request param)
    // Example: DELETE /api/watchlist/10?userId=2
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, @RequestParam Long userId) {
        try {
            watchlistService.delete(id, userId);
            return ResponseEntity.ok(ResponseUtil.buildResponse(
                    Map.of("deleted", true, "id", id, "userId", userId),
                    true,
                    null
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}
