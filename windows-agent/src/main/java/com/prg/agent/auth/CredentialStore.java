package com.prg.agent.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prg.agent.config.AgentConfig;
import com.prg.agent.util.CryptoUtil;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists and loads encrypted credentials to/from disk.
 *
 * <p>The credentials file is stored at {data.dir}/credentials.enc and contains
 * a JSON payload encrypted with AES-256-GCM, using a machine-specific key
 * derived from the hardware ID.
 */
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);
    private static final String CREDENTIALS_FILE = "credentials.enc";

    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final Path credentialsPath;

    public CredentialStore(AgentConfig config, CryptoUtil cryptoUtil, ObjectMapper objectMapper) {
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.credentialsPath = Path.of(config.getDataDir(), CREDENTIALS_FILE);
    }

    /**
     * Saves credentials to encrypted file.
     */
    public void save(SavedCredentials credentials) {
        try {
            String json = objectMapper.writeValueAsString(credentials);
            String encrypted = cryptoUtil.encrypt(json);
            Files.writeString(credentialsPath, encrypted);
            log.info("Credentials saved to {}", credentialsPath);
        } catch (Exception e) {
            log.error("Failed to save credentials", e);
            throw new RuntimeException("Failed to save credentials", e);
        }
    }

    /**
     * Loads credentials from encrypted file.
     *
     * @return the saved credentials, or null if the file does not exist or cannot be decrypted
     */
    public SavedCredentials load() {
        if (!Files.exists(credentialsPath)) {
            log.debug("No credentials file found at {}", credentialsPath);
            return null;
        }

        try {
            String encrypted = Files.readString(credentialsPath);
            String json = cryptoUtil.decrypt(encrypted);
            SavedCredentials credentials = objectMapper.readValue(json, SavedCredentials.class);
            log.info("Credentials loaded from {}", credentialsPath);
            return credentials;
        } catch (Exception e) {
            log.warn("Failed to load credentials (file may be corrupted or from different machine): {}", e.getMessage());
            return null;
        }
    }

    /**
     * Deletes the credentials file.
     */
    public void delete() {
        try {
            Files.deleteIfExists(credentialsPath);
            log.info("Credentials deleted from {}", credentialsPath);
        } catch (Exception e) {
            log.error("Failed to delete credentials file", e);
        }
    }

    /**
     * Checks if a credentials file exists.
     */
    public boolean exists() {
        return Files.exists(credentialsPath);
    }

    /**
     * Data class representing saved credentials.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SavedCredentials {

        @JsonProperty("device_id")
        private String deviceId;

        @JsonProperty("refresh_token")
        private String refreshToken;

        @JsonProperty("server_url")
        private String serverUrl;

        @JsonProperty("tenant_name")
        private String tenantName;

        public SavedCredentials(String deviceId, String refreshToken, String serverUrl, String tenantName) {
            this.deviceId = deviceId;
            this.refreshToken = refreshToken;
            this.serverUrl = serverUrl;
            this.tenantName = tenantName;
        }
    }
}
