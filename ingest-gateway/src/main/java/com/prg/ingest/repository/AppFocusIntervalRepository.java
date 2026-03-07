package com.prg.ingest.repository;

import com.prg.ingest.entity.AppFocusInterval;
import com.prg.ingest.entity.AppFocusIntervalId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Set;
import java.util.UUID;

public interface AppFocusIntervalRepository extends JpaRepository<AppFocusInterval, AppFocusIntervalId> {

    @Query("SELECT a.id FROM AppFocusInterval a WHERE a.id IN :ids")
    Set<UUID> findExistingIds(@Param("ids") Set<UUID> ids);
}
