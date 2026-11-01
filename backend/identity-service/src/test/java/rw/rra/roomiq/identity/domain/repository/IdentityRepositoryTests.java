package rw.rra.roomiq.identity.domain.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.IdentityAuditOutboxEntry;
import rw.rra.roomiq.identity.domain.entity.Permission;
import rw.rra.roomiq.identity.domain.entity.Role;
import rw.rra.roomiq.identity.domain.entity.RolePermission;
import rw.rra.roomiq.identity.domain.entity.UserPrivilege;
import rw.rra.roomiq.identity.domain.entity.UserRole;
import rw.rra.roomiq.identity.domain.entity.UserSession;
import rw.rra.roomiq.identity.domain.entity.UserStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-repository-test",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@Transactional
class IdentityRepositoryTests {
    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RolePermissionRepository rolePermissionRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private UserPrivilegeRepository userPrivilegeRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private IdentityAuditOutboxRepository identityAuditOutboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

        @PersistenceContext
        private EntityManager entityManager;

    @Test
    void flywayVersionTwoCreatesPersistentAuditOutbox() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '2' AND \"success\" = TRUE",
                Integer.class)).isEqualTo(1);

        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        IdentityAuditOutboxEntry entry = identityAuditOutboxRepository.saveAndFlush(
                new IdentityAuditOutboxEntry(eventId, "IDENTITY_USER_CREATED",
                        "{\"eventId\":\"" + eventId + "\",\"schemaVersion\":1}", now));

        assertThat(identityAuditOutboxRepository.findById(eventId)).contains(entry);
        assertThat(entry.getPayload()).contains("\"schemaVersion\":1");
    }

    @Test
    void repositoriesPersistAndQueryIdentityRelationships() {
        AppUser user = appUserRepository.save(new AppUser("repository-test@rra.rw", "Repository Test", UserStatus.ACTIVE));
        Role role = roleRepository.save(new Role("REPOSITORY_ROLE", "Repository role", false));
        Permission permission = permissionRepository.save(new Permission("REPOSITORY_READ", "Repository read"));
        UUID buildingId = UUID.randomUUID();
        Instant now = Instant.now();

        rolePermissionRepository.save(new RolePermission(role, permission));
        userRoleRepository.save(new UserRole(user, role, buildingId, null));
        userPrivilegeRepository.save(new UserPrivilege(user, "CG_BOOKING", null, true));
        UserSession session = userSessionRepository.save(
                new UserSession(user, "repository-refresh-hash", now, now.plusSeconds(3600)));

        assertThat(appUserRepository.findByEmailIgnoreCase("REPOSITORY-TEST@RRA.RW")).contains(user);
        assertThat(appUserRepository.findAllByStatus(UserStatus.ACTIVE)).contains(user);
        assertThat(roleRepository.findByCodeIgnoreCase("repository_role")).contains(role);
        assertThat(permissionRepository.findByCodeIgnoreCase("repository_read")).contains(permission);
        assertThat(rolePermissionRepository.existsByRoleIdAndPermissionId(role.getId(), permission.getId())).isTrue();
        assertThat(userRoleRepository.findAllByUserIdAndScopeOfficeBuildingId(user.getId(), buildingId))
                .hasSize(1);
        assertThat(userPrivilegeRepository.findAllByUserIdAndPrivilegeCodeAndActiveTrue(user.getId(), "CG_BOOKING"))
                .hasSize(1);
        assertThat(userSessionRepository.findByRefreshTokenHash("repository-refresh-hash")).contains(session);
        assertThat(userSessionRepository.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(user.getId(), now))
                .contains(session);

        user.markDeleted();
        appUserRepository.flush();

        assertThat(appUserRepository.findByEmailIgnoreCase(user.getEmail())).isEmpty();
    }

    @Test
    void organizationReferencesRemainIdentifierOnlyAcrossServiceBoundary() {
        UUID departmentId = UUID.randomUUID();
        UUID officeBuildingId = UUID.randomUUID();
        AppUser user = new AppUser("id-only-reference@rra.rw", "ID-only Reference", UserStatus.PENDING);
        user.updateProfile(null, null, null, null, null, departmentId, officeBuildingId);
        user = appUserRepository.saveAndFlush(user);

        Role role = roleRepository.saveAndFlush(new Role("ID_ONLY_SCOPE_ROLE", "ID-only scope role", false));
        UserRole assignment = userRoleRepository.saveAndFlush(
                new UserRole(user, role, officeBuildingId, null));

        assertThat(user.getDepartmentId()).isEqualTo(departmentId);
        assertThat(user.getOfficeBuildingId()).isEqualTo(officeBuildingId);
        assertThat(assignment.getScopeOfficeBuildingId()).isEqualTo(officeBuildingId);
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(entityType -> entityType.getJavaType().getSimpleName())
                .doesNotContain("Country", "Province", "District", "OfficeBuilding", "Floor", "Department");
        assertThat(entityManager.getMetamodel().entity(AppUser.class).getAttribute("departmentId").getJavaType())
                .isEqualTo(UUID.class);
        assertThat(entityManager.getMetamodel().entity(AppUser.class).getAttribute("officeBuildingId").getJavaType())
                .isEqualTo(UUID.class);
        assertThat(importedForeignKeyColumns("APP_USER"))
                .doesNotContain("DEPARTMENT_ID", "OFFICE_BUILDING_ID");
        assertThat(importedForeignKeyColumns("USER_ROLE")).doesNotContain("SCOPE_OFFICE_BUILDING_ID");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = 'PUBLIC'
                  AND TABLE_NAME IN ('COUNTRY', 'PROVINCE', 'DISTRICT', 'OFFICE_BUILDING', 'FLOOR', 'DEPARTMENT')
                """, Integer.class)).isZero();
    }

    @Test
    void notificationRecipientQueryReturnsOnlyActiveAdminsInTheBuildingScope() {
        UUID buildingId = UUID.randomUUID();
        UUID otherBuildingId = UUID.randomUUID();
        Role adminRole = roleRepository.saveAndFlush(new Role("ADMIN", "Building Admin", false));
        Role secretaryRole = roleRepository.saveAndFlush(new Role("SECRETARY", "Secretary", false));
        AppUser activeAdmin = appUserRepository.saveAndFlush(
                new AppUser("active-admin-" + UUID.randomUUID() + "@rra.rw", "Active Admin", UserStatus.ACTIVE));
        AppUser inactiveAdmin = appUserRepository.saveAndFlush(
                new AppUser("inactive-admin-" + UUID.randomUUID() + "@rra.rw", "Inactive Admin", UserStatus.DISABLED));
        AppUser otherBuildingAdmin = appUserRepository.saveAndFlush(
                new AppUser("other-admin-" + UUID.randomUUID() + "@rra.rw", "Other Building Admin",
                        UserStatus.ACTIVE));
        AppUser activeSecretary = appUserRepository.saveAndFlush(
                new AppUser("active-secretary-" + UUID.randomUUID() + "@rra.rw", "Active Secretary",
                        UserStatus.ACTIVE));

        userRoleRepository.saveAndFlush(new UserRole(activeAdmin, adminRole, buildingId, null));
        userRoleRepository.saveAndFlush(new UserRole(inactiveAdmin, adminRole, buildingId, null));
        userRoleRepository.saveAndFlush(new UserRole(otherBuildingAdmin, adminRole, otherBuildingId, null));
        userRoleRepository.saveAndFlush(new UserRole(activeSecretary, secretaryRole, buildingId, null));

        assertThat(userRoleRepository.findActiveAdminUserIdsByBuildingId(buildingId))
                .containsExactly(activeAdmin.getId());
    }

        private Set<String> importedForeignKeyColumns(String tableName) {
                try (Connection connection = jdbcTemplate.getDataSource().getConnection();
                         ResultSet importedKeys = connection.getMetaData().getImportedKeys(
                                         connection.getCatalog(), "PUBLIC", tableName)) {
                        Set<String> columns = new HashSet<>();
                        while (importedKeys.next()) {
                                columns.add(importedKeys.getString("FKCOLUMN_NAME"));
                        }
                        return columns;
                } catch (SQLException exception) {
                        throw new AssertionError("Could not inspect imported foreign keys for " + tableName, exception);
                }
        }
}
