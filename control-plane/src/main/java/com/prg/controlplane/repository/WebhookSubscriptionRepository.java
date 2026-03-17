package com.prg.controlplane.repository;

import com.prg.controlplane.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findByTenantId(UUID tenantId);

    Optional<WebhookSubscription> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query(value = """
        SELECT * FROM webhook_subscriptions
        WHERE tenant_id = :tenantId AND active = true
          AND event_types @> CAST(:eventType AS jsonb)
        """, nativeQuery = true)
    List<WebhookSubscription> findActiveByTenantIdAndEventType(
        @Param("tenantId") UUID tenantId,
        @Param("eventType") String eventType);
}
