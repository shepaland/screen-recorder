package com.prg.controlplane.repository;

import com.prg.controlplane.entity.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {
    Page<WebhookDelivery> findBySubscriptionIdOrderByCreatedTsDesc(UUID subscriptionId, Pageable pageable);
}
