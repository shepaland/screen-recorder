package com.prg.auth.repository;

import com.prg.auth.entity.OAuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OAuthIdentityRepository extends JpaRepository<OAuthIdentity, UUID> {

    Optional<OAuthIdentity> findByProviderAndProviderSub(String provider, String providerSub);

    Optional<OAuthIdentity> findByEmail(String email);

    List<OAuthIdentity> findByEmailIn(List<String> emails);
}
