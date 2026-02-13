package com.djai.wealthadvisor.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;


@Component
public class PasswordHashUtil {

    
    public String hashPasswordSHA256(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    
    public boolean verifyPassword(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) {
            return false;
        }
        
        
        if (!isHexString(storedPassword)) {
            String hashedStored = hashPasswordSHA256(storedPassword);
            return hashedStored.equals(rawPassword);
        }
        
       
        return storedPassword.equals(rawPassword);
    }

    
    private boolean isHexString(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.matches("^[0-9a-fA-F]+$");
    }

   
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
