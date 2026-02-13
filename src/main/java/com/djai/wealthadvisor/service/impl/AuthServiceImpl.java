package com.djai.wealthadvisor.service.impl;

import com.djai.wealthadvisor.dto.AuthDto;
import com.djai.wealthadvisor.entity.User;
import com.djai.wealthadvisor.repository.UserRepository;
import com.djai.wealthadvisor.security.JwtUtil;
import com.djai.wealthadvisor.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    @Override
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = new User();
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        
        // SAVE HASH DIRECTLY: The 'request.getPassword()' is already the Hash from Frontend.
        // We save it directly to the DB without re-hashing.
        user.setPasswordHash(request.getPassword());
        
        user.setCashBalance(new BigDecimal("100000.00"));
        user.setIsActive(true);

        userRepository.save(user);

        // RETURN SUCCESS WITHOUT TOKEN
        // We pass null for token and ID because Signup doesn't log you in automatically anymore.
        return new AuthDto.AuthResponse(null, user.getFullName(), user.getEmail(), user.getId());
    }

    @Override
    public AuthDto.AuthResponse login(AuthDto.LoginRequest request) {
        try {
            // This compares the Hash from Frontend (request) vs Hash in DB
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new RuntimeException("Invalid email or password");
        }

        User user = userRepository.findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ONLY HERE WE GENERATE TOKEN
        String token = jwtUtil.generateToken(user.getEmail(), user.getEmail(), user.getId());

        return new AuthDto.AuthResponse(token, user.getFullName(), user.getEmail(), user.getId());
    }

    @Override
    public String generateResetToken(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("No account found"));
        return jwtUtil.generateToken(user.getEmail(), user.getEmail(), user.getId());
    }

    @Override
    public void resetPassword(AuthDto.ResetPasswordRequest request) {
        if (!jwtUtil.validateToken(request.getToken())) {
            throw new RuntimeException("Invalid Token");
        }
        String email = jwtUtil.extractEmail(request.getToken());
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Save the new hash directly (Frontend sends hash)
        user.setPasswordHash(request.getNewPassword());
        userRepository.save(user);
    }
}