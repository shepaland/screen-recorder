package com.prg.auth.service;

import com.prg.auth.dto.request.CreateDeviceTokenRequest;
import com.prg.auth.dto.request.UpdateDeviceTokenRequest;
import com.prg.auth.dto.response.DeviceTokenResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.dto.response.TokenDeviceResponse;
import com.prg.auth.entity.Device;
import com.prg.auth.entity.DeviceRegistrationToken;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.exception.ConflictException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.DeviceRegistrationTokenRepository;
import com.prg.auth.repository.DeviceRepository;
import com.prg.auth.repository.TenantRepository;
import com.prg.auth.repository.UserRepository;
import com.prg.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenService {

    private final DeviceRegistrationTokenRepository tokenRepository;
    private final DeviceRepository deviceRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final TokenEncryptionService tokenEncryptionService;

    @Transactional
    public DeviceTokenResponse createToken(CreateDeviceTokenRequest request, UserPrincipal principal,
                                            String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        User createdBy = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        String rawToken = "drt_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = AuthService.sha256(rawToken);
        String encryptedToken = tokenEncryptionService.encrypt(rawToken);

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .tenant(tenant)
                .tokenHash(tokenHash)
                .name(request.getName())
                .maxUses(request.getMaxUses())
                .currentUses(0)
                .expiresAt(request.getExpiresAt())
                .isActive(true)
                .recordingEnabled(request.getRecordingEnabled() != null ? request.getRecordingEnabled() : true)
                .createdBy(createdBy)
                .encryptedToken(encryptedToken)
                .build();
        token = tokenRepository.save(token);

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_CREATED", "DEVICE_TOKENS", token.getId(),
                Map.of("name", request.getName(),
                        "max_uses", request.getMaxUses() != null ? request.getMaxUses().toString() : "unlimited"),
                ipAddress, userAgent, getCorrelationId());

        log.info("Device registration token created: id={}, name={}, tenant_id={}",
                token.getId(), request.getName(), principal.getTenantId());

        return toResponse(token, rawToken, 0);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceTokenResponse> getTokens(UserPrincipal principal, int page, int size,
                                                        String search, Boolean isActive) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdTs"));
        Page<DeviceRegistrationToken> tokenPage = tokenRepository.findByTenantIdFiltered(
                principal.getTenantId(), search, isActive, pageRequest);

        // Batch count active (non-deleted) devices per token
        List<UUID> tokenIds = tokenPage.getContent().stream()
                .map(DeviceRegistrationToken::getId).toList();
        Map<UUID, Long> deviceCounts = getActiveDeviceCounts(tokenIds, principal.getTenantId());

        return PageResponse.<DeviceTokenResponse>builder()
                .content(tokenPage.getContent().stream()
                        .map(t -> toResponse(t, null,
                                deviceCounts.getOrDefault(t.getId(), 0L).intValue()))
                        .toList())
                .page(tokenPage.getNumber())
                .size(tokenPage.getSize())
                .totalElements(tokenPage.getTotalElements())
                .totalPages(tokenPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public DeviceTokenResponse getToken(UUID tokenId, UserPrincipal principal) {
        DeviceRegistrationToken token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device registration token not found", "TOKEN_NOT_FOUND"));

        if (!token.getTenant().getId().equals(principal.getTenantId())) {
            throw new ResourceNotFoundException(
                    "Device registration token not found", "TOKEN_NOT_FOUND");
        }

        Map<UUID, Long> counts = getActiveDeviceCounts(List.of(tokenId), principal.getTenantId());
        return toResponse(token, null, counts.getOrDefault(tokenId, 0L).intValue());
    }

    @Transactional
    public void deactivateToken(UUID tokenId, UserPrincipal principal, String ipAddress, String userAgent) {
        DeviceRegistrationToken token = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new ResourceNotFoundException("Device registration token not found", "TOKEN_NOT_FOUND"));

        if (!token.getTenant().getId().equals(principal.getTenantId())) {
            throw new ResourceNotFoundException("Device registration token not found", "TOKEN_NOT_FOUND");
        }

        token.setIsActive(false);
        tokenRepository.save(token);

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_DEACTIVATED", "DEVICE_TOKENS", tokenId,
                Map.of("name", token.getName()),
                ipAddress, userAgent, getCorrelationId());

        log.info("Device registration token deactivated: id={}, tenant_id={}", tokenId, principal.getTenantId());
    }

    @Transactional(readOnly = true)
    public TokenDeviceResponse getDevicesByToken(UUID tokenId, UUID tenantId) {
        DeviceRegistrationToken token = tokenRepository.findByIdAndTenantId(tokenId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device registration token not found", "DEVICE_TOKEN_NOT_FOUND"));

        List<Device> devices = deviceRepository.findByRegistrationTokenIdAndTenantId(tokenId, tenantId);

        List<TokenDeviceResponse.TokenDeviceItem> items = devices.stream()
                .map(d -> TokenDeviceResponse.TokenDeviceItem.builder()
                        .id(d.getId())
                        .hostname(d.getHostname())
                        .osInfo(d.getOsVersion())
                        .status(d.getStatus())
                        .isActive(d.getIsActive())
                        .isDeleted(d.getIsDeleted())
                        .lastHeartbeatTs(d.getLastHeartbeatTs())
                        .createdTs(d.getCreatedTs())
                        .build())
                .toList();

        return TokenDeviceResponse.builder()
                .tokenId(token.getId())
                .tokenName(token.getName())
                .devices(items)
                .totalCount(items.size())
                .build();
    }

    @Transactional(readOnly = true)
    public DeviceTokenResponse revealToken(UUID tokenId, UserPrincipal principal,
                                            String ipAddress, String userAgent) {
        DeviceRegistrationToken token = tokenRepository.findByIdAndTenantId(tokenId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device registration token not found", "TOKEN_NOT_FOUND"));

        if (token.getEncryptedToken() == null) {
            throw new ConflictException(
                    "Token was created before reveal support. Please create a new token.",
                    "TOKEN_REVEAL_NOT_AVAILABLE");
        }

        String rawToken = tokenEncryptionService.decrypt(token.getEncryptedToken());

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_REVEALED", "DEVICE_TOKENS", tokenId,
                Map.of("name", token.getName()),
                ipAddress, userAgent, getCorrelationId());

        log.info("Device registration token revealed: id={}, tenant_id={}, by_user={}",
                tokenId, principal.getTenantId(), principal.getUserId());

        return DeviceTokenResponse.builder()
                .id(token.getId())
                .token(rawToken)
                .name(token.getName())
                .build();
    }

    @Transactional
    public void hardDeleteToken(UUID tokenId, UserPrincipal principal,
                                String ipAddress, String userAgent) {
        DeviceRegistrationToken token = tokenRepository.findByIdAndTenantId(tokenId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device registration token not found", "TOKEN_NOT_FOUND"));

        // Detach devices from this token
        deviceRepository.detachFromToken(tokenId, principal.getTenantId());

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_HARD_DELETED", "DEVICE_TOKENS", tokenId,
                Map.of("name", token.getName()),
                ipAddress, userAgent, getCorrelationId());

        tokenRepository.delete(token);

        log.info("Device registration token hard deleted: id={}, tenant_id={}", tokenId, principal.getTenantId());
    }

    @Transactional
    public DeviceTokenResponse updateDeviceToken(UUID tokenId, UpdateDeviceTokenRequest request,
                                                  UserPrincipal principal, String ipAddress, String userAgent) {
        DeviceRegistrationToken token = tokenRepository.findByIdAndTenantId(tokenId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device registration token not found", "TOKEN_NOT_FOUND"));

        boolean recordingChanged = false;
        Map<String, Object> auditDetails = new java.util.HashMap<>();

        if (request.getName() != null) {
            auditDetails.put("old_name", token.getName());
            token.setName(request.getName());
            auditDetails.put("new_name", request.getName());
        }
        if (request.getMaxUses() != null) {
            auditDetails.put("old_max_uses", token.getMaxUses() != null ? token.getMaxUses().toString() : "unlimited");
            token.setMaxUses(request.getMaxUses());
            auditDetails.put("new_max_uses", request.getMaxUses().toString());
        }
        if (request.getRecordingEnabled() != null && !request.getRecordingEnabled().equals(token.getRecordingEnabled())) {
            auditDetails.put("old_recording_enabled", String.valueOf(token.getRecordingEnabled()));
            token.setRecordingEnabled(request.getRecordingEnabled());
            auditDetails.put("new_recording_enabled", String.valueOf(request.getRecordingEnabled()));
            recordingChanged = true;
        }

        token = tokenRepository.save(token);

        // If recording_enabled changed, propagate to device.settings for all devices of this token
        if (recordingChanged) {
            String jsonValue = request.getRecordingEnabled() ? "true" : "false";
            deviceRepository.updateRecordingEnabledByTokenId(tokenId, principal.getTenantId(), jsonValue);
            log.info("Updated recording_enabled={} for all devices of token_id={}, tenant_id={}",
                    request.getRecordingEnabled(), tokenId, principal.getTenantId());
        }

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_UPDATED", "DEVICE_TOKENS", tokenId,
                auditDetails.isEmpty() ? null : auditDetails,
                ipAddress, userAgent, getCorrelationId());

        log.info("Device registration token updated: id={}, tenant_id={}", tokenId, principal.getTenantId());

        Map<UUID, Long> counts = getActiveDeviceCounts(List.of(tokenId), principal.getTenantId());
        return toResponse(token, null, counts.getOrDefault(tokenId, 0L).intValue());
    }

    private Map<UUID, Long> getActiveDeviceCounts(List<UUID> tokenIds, UUID tenantId) {
        if (tokenIds.isEmpty()) return Map.of();
        List<Object[]> rows = deviceRepository.countActiveDevicesByTokenIds(tokenIds, tenantId);
        Map<UUID, Long> result = new java.util.HashMap<>();
        for (Object[] row : rows) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    private DeviceTokenResponse toResponse(DeviceRegistrationToken token, String rawToken, int deviceCount) {
        return DeviceTokenResponse.builder()
                .id(token.getId())
                .token(rawToken)
                .tokenPreview("drt_" + token.getTokenHash().substring(0, 4))
                .name(token.getName())
                .maxUses(token.getMaxUses())
                .currentUses(token.getCurrentUses())
                .deviceCount(deviceCount)
                .expiresAt(token.getExpiresAt())
                .isActive(token.getIsActive())
                .recordingEnabled(token.getRecordingEnabled())
                .createdByUsername(token.getCreatedBy() != null ? token.getCreatedBy().getUsername() : null)
                .createdTs(token.getCreatedTs())
                .build();
    }

    private UUID getCorrelationId() {
        String cid = MDC.get("correlation_id");
        if (cid != null) {
            try { return UUID.fromString(cid); } catch (IllegalArgumentException e) { return null; }
        }
        return null;
    }
}
