package com.djai.wealthadvisor.dto;

import lombok.Data;

public class AuthDto {

    @Data
    public static class LoginRequest {
        private String email;
        private String password;
    }

    @Data
    public static class RegisterRequest {
        private String fullName;
        private String email;
        private String password;
    }

    @Data
    public static class AuthResponse {
        private String token;
        private String fullName;
        private String email;
        private Long userId;
        private String message;

        public AuthResponse(String token, String fullName, String email, Long userId) {
            this.token = token;
            this.fullName = fullName;
            this.email = email;
            this.userId = userId;
            this.message = "Success";
        }
    }
    
    @Data
    public static class ResetPasswordRequest {
        private String token;       
        private String newPassword;
    }
}