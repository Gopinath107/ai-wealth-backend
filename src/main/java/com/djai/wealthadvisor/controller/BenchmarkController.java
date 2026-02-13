package com.djai.wealthadvisor.controller;

import com.djai.wealthadvisor.service.BenchmarkService;
import com.djai.wealthadvisor.util.ResponseUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    @GetMapping("/benchmarks")
    public ResponseEntity<Map<String, Object>> benchmarks() {
        try {
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(benchmarkService.getBenchmarks(), true, null)
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(
                    ResponseUtil.buildResponse(null, false, e)
            );
        }
    }
}
