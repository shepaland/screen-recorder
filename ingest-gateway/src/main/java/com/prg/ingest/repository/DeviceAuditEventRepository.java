package com.prg.ingest.repository;

import com.prg.ingest.entity.DeviceAuditEvent;
import com.prg.ingest.entity.DeviceAuditEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface DeviceAuditEventRepository extends JpaRepository<DeviceAuditEvent, DeviceAuditEventId> {

    @Query("SELECT e.id FROM DeviceAuditEvent e WHERE e.id IN :ids")
    Set<UUID> findExistingIds(@Param("ids") Set<UUID> ids);
}
