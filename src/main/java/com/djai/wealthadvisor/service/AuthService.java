package com.djai.wealthadvisor.service;

import com.djai.wealthadvisor.dto.AuthDto;

public interface AuthService {
    AuthDto.AuthResponse register(AuthDto.RegisterRequest request);
    AuthDto.AuthResponse login(AuthDto.LoginRequest request);
    
    String generateResetToken(String email);
    void resetPassword(AuthDto.ResetPasswordRequest request);
}