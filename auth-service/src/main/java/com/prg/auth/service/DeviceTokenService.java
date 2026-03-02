package com.prg.auth.service;

import com.prg.auth.dto.request.CreateDeviceTokenRequest;
import com.prg.auth.dto.response.DeviceTokenResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.entity.DeviceRegistrationToken;
import com.prg.auth.entity.Tenant;
import com.prg.auth.entity.User;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.DeviceRegistrationTokenRepository;
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

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenService {

    private final DeviceRegistrationTokenRepository tokenRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional
    public DeviceTokenResponse createToken(CreateDeviceTokenRequest request, UserPrincipal principal,
                                            String ipAddress, String userAgent) {
        Tenant tenant = tenantRepository.findById(principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        User createdBy = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found", "USER_NOT_FOUND"));

        String rawToken = "drt_" + UUID.randomUUID().toString().replace("-", "");
        String tokenHash = AuthService.sha256(rawToken);

        DeviceRegistrationToken token = DeviceRegistrationToken.builder()
                .tenant(tenant)
                .tokenHash(tokenHash)
                .name(request.getName())
                .maxUses(request.getMaxUses())
                .currentUses(0)
                .expiresAt(request.getExpiresAt())
                .isActive(true)
                .createdBy(createdBy)
                .build();
        token = tokenRepository.save(token);

        auditService.logAction(principal.getTenantId(), principal.getUserId(),
                "DEVICE_TOKEN_CREATED", "DEVICE_TOKENS", token.getId(),
                Map.of("name", request.getName(),
                        "max_uses", request.getMaxUses() != null ? request.getMaxUses().toString() : "unlimited"),
                ipAddress, userAgent, getCorrelationId());

        log.info("Device registration token created: id={}, name={}, tenant_id={}",
                token.getId(), request.getName(), principal.getTenantId());

        return toResponse(token, rawToken);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceTokenResponse> getTokens(UserPrincipal principal, int page, int size,
                                                        String search, Boolean isActive) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdTs"));
        Page<DeviceRegistrationToken> tokenPage = tokenRepository.findByTenantIdFiltered(
                principal.getTenantId(), search, isActive, pageRequest);

        return PageResponse.<DeviceTokenResponse>builder()
                .content(tokenPage.getContent().stream()
                        .map(t -> toResponse(t, null))
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

        return toResponse(token, null);
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

    private DeviceTokenResponse toResponse(DeviceRegistrationToken token, String rawToken) {
        return DeviceTokenResponse.builder()
                .id(token.getId())
                .token(rawToken)
                .tokenPreview("drt_" + token.getTokenHash().substring(0, 4))
                .name(token.getName())
                .maxUses(token.getMaxUses())
                .currentUses(token.getCurrentUses())
                .expiresAt(token.getExpiresAt())
                .isActive(token.getIsActive())
                .createdByUsername(token.getCreatedBy().getUsername())
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
