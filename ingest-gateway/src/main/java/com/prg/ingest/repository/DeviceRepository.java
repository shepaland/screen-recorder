package com.prg.ingest.repository;

import com.prg.ingest.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    List<Device> findByIdInAndTenantId(Collection<UUID> ids, UUID tenantId);

    Optional<Device> findByIdAndTenantId(UUID id, UUID tenantId);
}
