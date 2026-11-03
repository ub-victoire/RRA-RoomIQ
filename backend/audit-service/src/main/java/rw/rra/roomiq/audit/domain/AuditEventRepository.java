package rw.rra.roomiq.audit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends Repository<AuditEvent, UUID>, AuditEventSearchRepository {
    Optional<AuditEvent> findById(UUID id);

    long count();

    @Modifying
    @Query(value = """
            INSERT INTO audit_event
                (id, actor_user_id, action, resource_type, resource_id, scope_office_building_id,
                 outcome, correlation_id, metadata, occurred_at)
            VALUES
                (:id, :actorUserId, :action, :resourceType, :resourceId, :scopeOfficeBuildingId, :outcome,
                 :correlationId, CAST(:metadata AS jsonb), :occurredAt)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("actorUserId") UUID actorUserId,
                       @Param("action") String action, @Param("resourceType") String resourceType,
                       @Param("resourceId") UUID resourceId,
                       @Param("scopeOfficeBuildingId") UUID scopeOfficeBuildingId,
                       @Param("outcome") String outcome, @Param("correlationId") String correlationId,
                       @Param("metadata") String metadata,
                       @Param("occurredAt") Instant occurredAt);
}
