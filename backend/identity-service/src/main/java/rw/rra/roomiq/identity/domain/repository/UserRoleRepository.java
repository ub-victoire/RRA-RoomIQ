package rw.rra.roomiq.identity.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import rw.rra.roomiq.identity.domain.entity.UserRole;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {
    List<UserRole> findAllByUserId(UUID userId);

    List<UserRole> findAllByUserIdAndScopeOfficeBuildingId(UUID userId, UUID scopeOfficeBuildingId);

    boolean existsByUserIdAndRoleIdAndScopeOfficeBuildingId(UUID userId, UUID roleId, UUID scopeOfficeBuildingId);

    Optional<UserRole> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select distinct assignment.user.id
            from UserRole assignment
            where assignment.role.code = 'ADMIN'
              and assignment.scopeOfficeBuildingId = :buildingId
              and assignment.user.status = rw.rra.roomiq.identity.domain.entity.UserStatus.ACTIVE
            order by assignment.user.id
            """)
    List<UUID> findActiveAdminUserIdsByBuildingId(@Param("buildingId") UUID buildingId);
}
