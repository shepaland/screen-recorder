package com.prg.auth.repository;

import com.prg.auth.entity.AuditLog;
import com.prg.auth.entity.AuditLogId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, AuditLogId>, JpaSpecificationExecutor<AuditLog> {

}
