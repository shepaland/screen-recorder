package com.prg.auth.repository;

import com.prg.auth.entity.Invitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByToken(String token);

    boolean existsByTenantIdAndEmailAndAcceptedTsIsNull(UUID tenantId, String email);
}
