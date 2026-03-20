package com.prg.auth.service;

import com.prg.auth.entity.*;
import com.prg.auth.exception.ConflictException;
import com.prg.auth.exception.ResourceNotFoundException;
import com.prg.auth.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvitationService {

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantMembershipRepository membershipRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${prg.invite-url-base:https://services-test.shepaland.ru/screenrecorder}")
    private String inviteUrlBase;

    /**
     * Invite a user to join a tenant by email.
     * If user already exists — add membership directly.
     * If not — create invitation and send email.
     */
    @Transactional
    public void invite(UUID tenantId, String email, UUID roleId, UUID invitedBy) {
        String normalizedEmail = email.trim().toLowerCase();

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        Role role;
        if (roleId != null) {
            role = roleRepository.findByIdAndTenantId(roleId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Role not found", "ROLE_NOT_FOUND"));
        } else {
            // Default role: VIEWER or first available
            List<Role> tenantRoles = roleRepository.findByTenantId(tenantId);
            role = tenantRoles.stream().filter(r -> "VIEWER".equals(r.getCode())).findFirst()
                    .or(() -> tenantRoles.stream().filter(r -> "OPERATOR".equals(r.getCode())).findFirst())
                    .or(() -> tenantRoles.stream().findFirst())
                    .orElseThrow(() -> new ResourceNotFoundException("No roles in tenant", "ROLE_NOT_FOUND"));
            log.info("Default role for invite: {} ({})", role.getCode(), role.getId());
        }

        // Check if user already exists
        var existingUser = userRepository.findActiveByEmail(normalizedEmail);

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            // Check if already a member
            if (membershipRepository.existsByUserIdAndTenantIdAndIsActiveTrue(user.getId(), tenantId)) {
                throw new ConflictException("User is already a member of this tenant");
            }
            // Add membership directly
            TenantMembership membership = TenantMembership.builder()
                    .user(user)
                    .tenant(tenant)
                    .roles(Set.of(role))
                    .isActive(true)
                    .isDefault(false)
                    .build();
            membershipRepository.save(membership);
            log.info("User {} added to tenant {} with role {}", normalizedEmail, tenant.getName(), role.getCode());
            return;
        }

        // Check for pending invitation
        if (invitationRepository.existsByTenantIdAndEmailAndAcceptedTsIsNull(tenantId, normalizedEmail)) {
            throw new ConflictException("Invitation already sent to this email");
        }

        // Create invitation
        Invitation invitation = Invitation.builder()
                .tenantId(tenantId)
                .email(normalizedEmail)
                .roleId(role.getId())
                .token(UUID.randomUUID().toString())
                .invitedBy(invitedBy)
                .expiresAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build();
        invitationRepository.save(invitation);

        // Send email
        String inviteLink = inviteUrlBase + "/invite/" + invitation.getToken();
        try {
            emailService.sendInvitation(normalizedEmail, tenant.getName(), inviteLink);
        } catch (Exception e) {
            log.warn("Failed to send invitation email to {} (invitation saved, link: {})",
                    normalizedEmail, inviteLink, e);
            // Don't throw — invitation is saved, user can get the link from admin
        }

        log.info("Invitation created for {} to tenant {}", normalizedEmail, tenant.getName());
    }

    /**
     * Accept an invitation: register (if new) and join the tenant.
     */
    @Transactional
    public User acceptInvitation(String token, String password, String firstName, String lastName) {
        Invitation invitation = invitationRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation not found", "INVITATION_NOT_FOUND"));

        if (invitation.isAccepted()) {
            throw new ConflictException("Invitation already accepted");
        }
        if (invitation.isExpired()) {
            throw new ConflictException("Invitation expired");
        }

        Tenant tenant = tenantRepository.findById(invitation.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found", "TENANT_NOT_FOUND"));

        Role role = roleRepository.findById(invitation.getRoleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found", "ROLE_NOT_FOUND"));

        // Find or create user
        User user = userRepository.findActiveByEmail(invitation.getEmail()).orElse(null);

        if (user == null) {
            // Create new user
            user = User.builder()
                    .tenant(tenant)  // primary tenant
                    .username(invitation.getEmail())
                    .email(invitation.getEmail())
                    .passwordHash(passwordEncoder.encode(password))
                    .firstName(firstName)
                    .lastName(lastName)
                    .authProvider("password")
                    .isActive(true)
                    .emailVerified(true)  // invited = verified
                    .build();
            user = userRepository.save(user);
        }

        // Add membership
        if (!membershipRepository.existsByUserIdAndTenantIdAndIsActiveTrue(user.getId(), tenant.getId())) {
            TenantMembership membership = TenantMembership.builder()
                    .user(user)
                    .tenant(tenant)
                    .roles(Set.of(role))
                    .isActive(true)
                    .isDefault(false)
                    .build();
            membershipRepository.save(membership);
        }

        // Mark invitation as accepted
        invitation.setAcceptedTs(Instant.now());
        invitationRepository.save(invitation);

        log.info("Invitation accepted: {} joined tenant {}", invitation.getEmail(), tenant.getName());
        return user;
    }
}
