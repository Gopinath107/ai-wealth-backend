package com.djai.wealthadvisor.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.djai.wealthadvisor.dto.AuthDto;
import com.djai.wealthadvisor.service.AuthService;
import com.djai.wealthadvisor.util.ResponseUtil;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody AuthDto.RegisterRequest request) {
        try {
            authService.register(request);
            // No Token returned here. Just success message.
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ResponseUtil.buildResponse("User registered successfully. Please Login.", true, null));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthDto.LoginRequest request) {
        try {
            // Token IS returned here
            AuthDto.AuthResponse response = authService.login(request);
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(response, true, null)
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        String currentUserEmail = "Unknown";
        if (auth != null) {
            currentUserEmail = auth.getName(); 
        }
	
        System.out.println("User logged out: " + currentUserEmail);

        return ResponseEntity.ok(
                ResponseUtil.buildResponse("Logout successful for " + currentUserEmail + " (Token cleared on client)", true, null)
        );
    }
    
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestParam String email) {
        try {
            String resetToken = authService.generateResetToken(email);
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse(resetToken, true, null)
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ResponseUtil.buildResponse(null, false, e));
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody AuthDto.ResetPasswordRequest request) {
        try {
            authService.resetPassword(request);
            return ResponseEntity.ok(
                    ResponseUtil.buildResponse("Password updated. Please login.", true, null)
            );
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ResponseUtil.buildResponse(null, false, e));
        }
    }
}