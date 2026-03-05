package com.prg.auth.repository;

import com.prg.auth.entity.DisposableEmailDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DisposableEmailDomainRepository extends JpaRepository<DisposableEmailDomain, String> {

    /**
     * Always use COUNT (not EXISTS) for timing-safe disposable domain check.
     * Same query plan for any domain, preventing timing side-channel.
     */
    @Query("SELECT COUNT(d) FROM DisposableEmailDomain d WHERE d.domain = :domain")
    long countByDomain(@Param("domain") String domain);
}
