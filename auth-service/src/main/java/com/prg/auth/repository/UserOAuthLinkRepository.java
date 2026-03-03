package com.prg.auth.repository;

import com.prg.auth.entity.UserOAuthLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserOAuthLinkRepository extends JpaRepository<UserOAuthLink, UUID> {

    List<UserOAuthLink> findByOauthIdentityId(UUID oauthIdentityId);

    Optional<UserOAuthLink> findByUserId(UUID userId);

    Optional<UserOAuthLink> findByUserIdAndOauthIdentityId(UUID userId, UUID oauthIdentityId);

    boolean existsByUserIdAndOauthIdentityId(UUID userId, UUID oauthIdentityId);

    @Query("SELECT uol FROM UserOAuthLink uol " +
           "JOIN FETCH uol.user u " +
           "JOIN FETCH u.tenant t " +
           "WHERE uol.oauthIdentity.id = :oauthId " +
           "AND u.isActive = true AND t.isActive = true")
    List<UserOAuthLink> findActiveLinksWithUserAndTenant(@Param("oauthId") UUID oauthId);
}
