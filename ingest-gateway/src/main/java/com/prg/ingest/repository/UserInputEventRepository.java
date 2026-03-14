package com.prg.ingest.repository;

import com.prg.ingest.entity.UserInputEvent;
import com.prg.ingest.entity.UserInputEventId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserInputEventRepository extends JpaRepository<UserInputEvent, UserInputEventId> {

    @Query("SELECT e.id FROM UserInputEvent e WHERE e.id IN :ids")
    Set<UUID> findExistingIds(@Param("ids") Set<UUID> ids);

    @Query(value = """
        SELECT * FROM user_input_events e
        WHERE e.tenant_id = :tenantId
          AND e.event_ts >= :from
          AND e.event_ts < :to
          AND (CAST(:eventTypes AS text) IS NULL OR e.event_type = ANY(string_to_array(CAST(:eventTypes AS text), ',')))
          AND (CAST(:username AS text) IS NULL OR e.username = CAST(:username AS text))
          AND (CAST(:deviceId AS text) IS NULL OR e.device_id = CAST(:deviceId AS uuid))
          AND (CAST(:search AS text) IS NULL
               OR LOWER(COALESCE(e.process_name, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
               OR LOWER(COALESCE(e.window_title, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
               OR LOWER(COALESCE(e.ui_element_name, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))
        """,
        countQuery = """
        SELECT COUNT(*) FROM user_input_events e
        WHERE e.tenant_id = :tenantId
          AND e.event_ts >= :from
          AND e.event_ts < :to
          AND (CAST(:eventTypes AS text) IS NULL OR e.event_type = ANY(string_to_array(CAST(:eventTypes AS text), ',')))
          AND (CAST(:username AS text) IS NULL OR e.username = CAST(:username AS text))
          AND (CAST(:deviceId AS text) IS NULL OR e.device_id = CAST(:deviceId AS uuid))
          AND (CAST(:search AS text) IS NULL
               OR LOWER(COALESCE(e.process_name, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
               OR LOWER(COALESCE(e.window_title, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))
               OR LOWER(COALESCE(e.ui_element_name, '')) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))
        """,
        nativeQuery = true)
    Page<UserInputEvent> findByFilters(
            @Param("tenantId") UUID tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("eventTypes") String eventTypes,
            @Param("username") String username,
            @Param("deviceId") UUID deviceId,
            @Param("search") String search,
            Pageable pageable);
}
