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

    @Query("""
        SELECT e FROM UserInputEvent e
        WHERE e.tenantId = :tenantId
          AND e.eventTs >= :from
          AND e.eventTs < :to
          AND (:eventTypes IS NULL OR e.eventType IN :eventTypes)
          AND (:username IS NULL OR e.username = :username)
          AND (:deviceId IS NULL OR e.deviceId = :deviceId)
          AND (:search IS NULL
               OR LOWER(e.processName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(e.windowTitle) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(e.uiElementName) LIKE LOWER(CONCAT('%', :search, '%')))
        """)
    Page<UserInputEvent> findByFilters(
            @Param("tenantId") UUID tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("eventTypes") List<String> eventTypes,
            @Param("username") String username,
            @Param("deviceId") UUID deviceId,
            @Param("search") String search,
            Pageable pageable);
}
