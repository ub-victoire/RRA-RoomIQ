package rw.rra.roomiq.lifecycle.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LifecyclePolicyVersionRepository extends JpaRepository<LifecyclePolicyVersion, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select version from LifecyclePolicyVersion version where version.policyVersionId = :id")
    java.util.Optional<LifecyclePolicyVersion> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select version from LifecyclePolicyVersion version
            where version.effectiveFrom <= :effectiveAt
              and (version.effectiveTo is null or version.effectiveTo > :effectiveAt)
              and version.status <> 'DRAFT'
            """)
    List<LifecyclePolicyVersion> findEffectiveAt(@Param("effectiveAt") Instant effectiveAt);

    @Query("""
            select version from LifecyclePolicyVersion version
            where version.status in ('APPROVED', 'SUPERSEDED')
              and version.effectiveFrom < :effectiveTo
              and (version.effectiveTo is null or version.effectiveTo > :effectiveFrom)
            """)
    List<LifecyclePolicyVersion> findOverlappingWithEnd(@Param("effectiveFrom") Instant effectiveFrom,
                                                        @Param("effectiveTo") Instant effectiveTo);

    @Query("""
            select version from LifecyclePolicyVersion version
            where version.status in ('APPROVED', 'SUPERSEDED')
              and (version.effectiveTo is null or version.effectiveTo > :effectiveFrom)
            """)
    List<LifecyclePolicyVersion> findOverlappingWithoutEnd(@Param("effectiveFrom") Instant effectiveFrom);
}
