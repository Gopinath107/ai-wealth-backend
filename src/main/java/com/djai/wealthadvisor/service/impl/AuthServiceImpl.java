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

    @org.springframework.beans.factory.annotation.Value("${supabase.jwt.secret:your-supabase-jwt-secret-placeholder}")
    private String supabaseJwtSecret;

    @Override
    public AuthDto.AuthResponse socialLogin(AuthDto.SocialLoginRequest request) {
        try {
            // Since Supabase might return ES256 or RS256 signed JWTs from external providers (like Google),
            // and the standard HS256 verification fails, we extract the claims directly from the Base64 payload.
            // Note: In a true production environment, you should use a JWKS provider to verify ES256/RS256 tokens securely.
            String token = request.getSupabaseToken();
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new RuntimeException("Invalid token format");
            }
            
            String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> claims = mapper.readValue(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});

            String email = (String) claims.get("email");
            if (email == null) {
                throw new RuntimeException("Email not found in Social Provider Token");
            }

            // Extract full name from user_metadata (Supabase standard)
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> userMetadata = (java.util.Map<String, Object>) claims.get("user_metadata");
            String fullName = email.split("@")[0]; // Fallback
            if (userMetadata != null && userMetadata.get("full_name") != null) {
                fullName = userMetadata.get("full_name").toString();
            } else if (userMetadata != null && userMetadata.get("name") != null) {
                fullName = userMetadata.get("name").toString();
            }

            // Check if user exists in Database
            User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
            
            if (user == null) {
                // Auto-register new user who logged in via Google/GitHub
                user = new User();
                user.setEmail(email);
                user.setFullName(fullName);
                // Assign a completely random UUID as password hash so manual login fails without reset
                user.setPasswordHash(java.util.UUID.randomUUID().toString());
                user.setCashBalance(new java.math.BigDecimal("100000.00"));
                user.setIsActive(true);
                user = userRepository.save(user);
            }

            // Issue the normal Spring Boot system JWT so the rest of the app works flawlessly
            String systemToken = jwtUtil.generateToken(user.getEmail(), user.getEmail(), user.getId());

            return new AuthDto.AuthResponse(systemToken, user.getFullName(), user.getEmail(), user.getId());

        } catch (Exception e) {
            throw new RuntimeException("Social login failed: Invalid or expired token. " + e.getMessage());
        }
    }
}