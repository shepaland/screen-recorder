package com.prg.auth.controller;

import com.prg.auth.dto.request.ChangePasswordRequest;
import com.prg.auth.dto.request.CreateUserRequest;
import com.prg.auth.dto.request.SetPasswordRequest;
import com.prg.auth.dto.request.UpdateProfileRequest;
import com.prg.auth.dto.request.UpdateUserRequest;
import com.prg.auth.dto.request.UpdateUserSettingsRequest;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.dto.response.UpdateProfileResponse;
import com.prg.auth.dto.response.UserResponse;
import com.prg.auth.dto.response.UserSettingsResponse;
import com.prg.auth.exception.AccessDeniedException;
import com.prg.auth.security.UserPrincipal;
import com.prg.auth.service.EmailOtpService;
import com.prg.auth.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final EmailOtpService emailOtpService;

    @GetMapping
    public ResponseEntity<PageResponse<UserResponse>> getUsers(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "created_ts,desc") String sort,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean isActive) {
        requirePermission(principal, "USERS:READ");
        PageResponse<UserResponse> response = userService.getUsers(
                principal.getTenantId(), search, isActive, page, size, sort);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        UserResponse response = userService.getCurrentUser(principal.getUserId(), principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        requirePermission(principal, "USERS:READ");
        UserResponse response = userService.getUserById(id, principal.getTenantId());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "USERS:CREATE");
        UserResponse response = userService.createUser(
                request, principal.getTenantId(), principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "USERS:UPDATE");
        UserResponse response = userService.updateUser(
                id, request, principal.getTenantId(), principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        requirePermission(principal, "USERS:DELETE");
        userService.deactivateUser(id, principal.getTenantId(), principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable UUID id,
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        // Self or USERS:UPDATE permission
        boolean isSelf = principal.getUserId().equals(id);
        if (!isSelf) {
            requirePermission(principal, "USERS:UPDATE");
        }
        userService.changePassword(id, request, principal.getTenantId(), principal,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /api/v1/users/me/settings
     * Update the current user's personal settings (e.g. session TTL).
     */
    @PutMapping("/me/settings")
    public ResponseEntity<UserSettingsResponse> updateMySettings(
            @Valid @RequestBody UpdateUserSettingsRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        UserSettingsResponse response = userService.updateUserSettings(
                principal.getUserId(), principal.getTenantId(), request,
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/v1/users/me/profile
     * Update current user's first name and last name (onboarding).
     */
    @PutMapping("/me/profile")
    public ResponseEntity<UpdateProfileResponse> updateMyProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        UpdateProfileResponse response = emailOtpService.updateProfile(
                principal.getUserId(), principal.getTenantId(),
                request.getFirstName(), request.getLastName(),
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/users/me/password
     * Set or change password for the current user.
     */
    @PostMapping("/me/password")
    public ResponseEntity<Void> setMyPassword(
            @Valid @RequestBody SetPasswordRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {
        emailOtpService.setPassword(
                principal.getUserId(), principal.getTenantId(),
                request.getCurrentPassword(), request.getNewPassword(),
                getClientIp(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    private void requirePermission(UserPrincipal principal, String permission) {
        if (!principal.hasPermission(permission)) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
