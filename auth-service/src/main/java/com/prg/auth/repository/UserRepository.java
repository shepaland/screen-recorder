package com.prg.auth.repository;

import com.prg.auth.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndUsername(UUID tenantId, String username);

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    Optional<User> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndUsername(UUID tenantId, String username);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    Page<User> findByTenantId(UUID tenantId, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.tenant.id = :tenantId " +
           "AND (:search IS NULL OR :search = '' " +
           "OR LOWER(u.username) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) " +
           "OR LOWER(u.lastName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))) " +
           "AND (:isActive IS NULL OR u.isActive = :isActive)")
    Page<User> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("search") String search,
            @Param("isActive") Boolean isActive,
            Pageable pageable
    );

    @Query("SELECT u FROM User u JOIN FETCH u.roles r JOIN FETCH r.permissions JOIN u.tenant t " +
           "WHERE u.username = :username AND u.isActive = true AND t.isActive = true " +
           "AND u.passwordHash IS NOT NULL")
    List<User> findActivePasswordUsersByUsername(@Param("username") String username);

    @Query("SELECT u FROM User u JOIN FETCH u.roles r JOIN FETCH r.permissions JOIN u.tenant t " +
           "WHERE LOWER(u.email) = LOWER(:email) AND u.isActive = true AND t.isActive = true " +
           "ORDER BY t.createdTs ASC")
    List<User> findActiveUsersByEmail(@Param("email") String email);

    @Query("SELECT u FROM User u JOIN FETCH u.tenant t LEFT JOIN FETCH u.roles " +
           "WHERE u.username = :username AND u.isActive = true AND t.isActive = true " +
           "ORDER BY t.createdTs ASC")
    List<User> findActiveUsersByUsername(@Param("username") String username);

    @Modifying
    @Query("UPDATE User u SET u.lastLoginTs = :loginTs WHERE u.id = :userId")
    void updateLastLoginTs(@Param("userId") UUID userId, @Param("loginTs") Instant loginTs);

    @Modifying
    @Query(value = "UPDATE devices SET user_id = NULL WHERE user_id = :userId", nativeQuery = true)
    void nullifyDevicesUser(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE recording_sessions SET user_id = NULL WHERE user_id = :userId", nativeQuery = true)
    void nullifyRecordingSessionsUser(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE device_commands SET created_by = NULL WHERE created_by = :userId", nativeQuery = true)
    void nullifyDeviceCommandsCreatedBy(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "UPDATE device_registration_tokens SET created_by = NULL WHERE created_by = :userId", nativeQuery = true)
    void nullifyDeviceTokensCreatedBy(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM user_roles WHERE user_id = :userId", nativeQuery = true)
    void deleteUserRoles(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE user_id = :userId", nativeQuery = true)
    void deleteRefreshTokens(@Param("userId") UUID userId);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE u.tenant.id = :tenantId AND r.code = :roleCode")
    long countByTenantIdAndRoleCode(@Param("tenantId") UUID tenantId, @Param("roleCode") String roleCode);
}
