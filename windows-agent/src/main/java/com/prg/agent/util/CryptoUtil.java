package com.prg.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility for securing credentials on disk.
 *
 * <p>The encryption key is derived from a combination of the machine's hardware ID
 * and a constant salt, making the encrypted data non-portable between machines.
 */
public class CryptoUtil {

    private static final Logger log = LoggerFactory.getLogger(CryptoUtil.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final String KEY_DERIVATION_SALT = "PRG-ScreenRecorder-AES256-v1";

    private final SecretKey secretKey;

    public CryptoUtil() {
        this.secretKey = deriveKey();
    }

    /**
     * Derives a 256-bit AES key from machine-specific data.
     * Uses hardware ID + constant salt, hashed with SHA-256.
     */
    private SecretKey deriveKey() {
        try {
            String hardwareId = HardwareId.getHardwareId();
            String keyMaterial = hardwareId + ":" + KEY_DERIVATION_SALT;

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));

            return new SecretKeySpec(keyBytes, ALGORITHM);
        } catch (Exception e) {
            log.error("Failed to derive encryption key", e);
            throw new RuntimeException("Cannot initialize encryption", e);
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     *
     * @param plaintext the text to encrypt
     * @return Base64-encoded string containing IV + ciphertext
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext: [IV (12 bytes)] [ciphertext + GCM tag]
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded AES-256-GCM ciphertext.
     *
     * @param ciphertext Base64-encoded string containing IV + ciphertext
     * @return the decrypted plaintext
     */
    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            if (combined.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Ciphertext too short");
            }

            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] encryptedData = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plaintext = cipher.doFinal(encryptedData);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
