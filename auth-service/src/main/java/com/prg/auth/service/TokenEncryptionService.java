package com.prg.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@Slf4j
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${app.token-encryption-key:}")
    private String encryptionKeyBase64;

    private SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            log.warn("Token encryption key not configured (app.token-encryption-key). Token reveal will not be available.");
            return;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Token encryption key must be 256 bits (32 bytes). Got: " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("Token encryption service initialized successfully");
        } catch (IllegalArgumentException e) {
            log.error("Invalid token encryption key: {}", e.getMessage());
            throw e;
        }
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    public String encrypt(String plaintext) {
        if (secretKey == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt token", e);
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        if (secretKey == null) {
            throw new IllegalStateException("Token encryption key not configured");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt token", e);
            throw new RuntimeException("Token decryption failed", e);
        }
    }
}
