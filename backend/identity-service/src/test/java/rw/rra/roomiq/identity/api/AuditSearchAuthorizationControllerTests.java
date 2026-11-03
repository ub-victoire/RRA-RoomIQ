package rw.rra.roomiq.identity.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.Permission;
import rw.rra.roomiq.identity.domain.entity.Role;
import rw.rra.roomiq.identity.domain.entity.RolePermission;
import rw.rra.roomiq.identity.domain.entity.UserRole;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;
import rw.rra.roomiq.identity.domain.repository.IdentityAuthorizationRepository;
import rw.rra.roomiq.identity.domain.repository.PermissionRepository;
import rw.rra.roomiq.identity.domain.repository.RolePermissionRepository;
import rw.rra.roomiq.identity.domain.repository.RoleRepository;
import rw.rra.roomiq.identity.domain.repository.UserRoleRepository;
import rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-audit-search-authorization-test",
        "spring.datasource.username=sa",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
@Transactional
class AuditSearchAuthorizationControllerTests {
    private static final String AUTHORIZATION_PATH = "/api/v1/internal/authorization/audit-search";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Autowired
    private RolePermissionRepository rolePermissions;

    @Autowired
    private UserRoleRepository userRoles;

    @Autowired
    private IdentityAuthorizationRepository authorization;

    @Test
    void migrationRegistersAuditReadWithoutGrantingItToRoles() {
        Permission permission = permissions.findByCodeIgnoreCase(IdentityPermissionCodes.AUDIT_READ)
                .orElseThrow();
        Role superAdminRole = createRole("SUPER_ADMIN");
        Role adminRole = createRole("ADMIN");

        assertThat(rolePermissions.existsByRoleIdAndPermissionId(superAdminRole.getId(), permission.getId()))
                .isFalse();
        assertThat(rolePermissions.existsByRoleIdAndPermissionId(adminRole.getId(), permission.getId()))
                .isFalse();
    }

    @Test
    void activeGlobalSuperAdminWithAuditReadGetsGlobalAccess() throws Exception {
        AppUser superAdmin = createUser(UserStatus.ACTIVE);
        Role role = createRole("SUPER_ADMIN");
        grantAuditRead(role);
        userRoles.saveAndFlush(new UserRole(superAdmin, role, null, null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(superAdmin.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUserId").value(superAdmin.getId().toString()))
                .andExpect(jsonPath("$.accessScope").value("GLOBAL"))
                .andExpect(jsonPath("$.buildingIds").isEmpty());
    }

    @Test
    void globalSuperAdminWithoutAuditReadIsDeniedEvenWithPermissionAuthorityClaim() throws Exception {
        AppUser superAdmin = createUser(UserStatus.ACTIVE);
        Role role = createRole("SUPER_ADMIN");
        userRoles.saveAndFlush(new UserRole(superAdmin, role, null, null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(superAdmin.getId().toString()))
                                .authorities(new SimpleGrantedAuthority(IdentityPermissionCodes.AUDIT_READ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopedAdminReceivesOnlyPersistedBuildingAssignments() throws Exception {
        AppUser admin = createUser(UserStatus.ACTIVE);
        Role role = createRole("ADMIN");
        grantAuditRead(role);
        UUID firstBuilding = UUID.randomUUID();
        UUID secondBuilding = UUID.randomUUID();
        userRoles.saveAndFlush(new UserRole(admin, role, secondBuilding, null));
        userRoles.saveAndFlush(new UserRole(admin, role, firstBuilding, null));

        String forgedRequest = """
                {
                  "userId": "%s",
                  "role": "SUPER_ADMIN",
                  "permission": "IDENTITY_SYSTEM_ADMIN",
                  "globalAccess": true,
                  "buildingIds": ["%s"]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(admin.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(forgedRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUserId").value(admin.getId().toString()))
                .andExpect(jsonPath("$.accessScope").value("BUILDING_SCOPED"))
                .andExpect(jsonPath("$.buildingIds.length()").value(2))
                .andExpect(jsonPath("$.buildingIds").value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                                firstBuilding.toString(), secondBuilding.toString())));
    }

    @Test
    void scopedAdminWithoutAuditReadIsDenied() throws Exception {
        AppUser admin = createUser(UserStatus.ACTIVE);
        Role role = createRole("ADMIN");
        userRoles.saveAndFlush(new UserRole(admin, role, UUID.randomUUID(), null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(admin.getId().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveUserAndRevokedRoleAssignmentAreDenied() throws Exception {
        AppUser inactiveAdmin = createUser(UserStatus.SUSPENDED);
        Role adminRole = createRole("ADMIN");
        grantAuditRead(adminRole);
        userRoles.saveAndFlush(new UserRole(inactiveAdmin, adminRole, UUID.randomUUID(), null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(inactiveAdmin.getId().toString()))))
                .andExpect(status().isForbidden());

        AppUser revokedSuperAdmin = createUser(UserStatus.ACTIVE);
        Role superAdminRole = createRole("SUPER_ADMIN");
        grantAuditRead(superAdminRole);
        UserRole assignment = userRoles.saveAndFlush(
                new UserRole(revokedSuperAdmin, superAdminRole, null, null));
        assertThat(authorization.isActiveSuperAdminWithPermission(revokedSuperAdmin.getId(),
                UserStatus.ACTIVE, IdentityPermissionCodes.AUDIT_READ)).isTrue();
        userRoles.deleteById(assignment.getId());
        userRoles.flush();

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(revokedSuperAdmin.getId().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyAdminScopeAndOrdinaryAdminCannotObtainGlobalAccess() throws Exception {
        AppUser admin = createUser(UserStatus.ACTIVE);
        Role role = createRole("ADMIN");
        grantAuditRead(role);
        userRoles.saveAndFlush(new UserRole(admin, role, null, null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(admin.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"globalAccess\":true,\"buildingIds\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void callerSuppliedSuperAdminIdentityCannotAuthorizeAnotherUser() throws Exception {
        AppUser caller = createUser(UserStatus.ACTIVE);
        AppUser superAdmin = createUser(UserStatus.ACTIVE);
        Role superAdminRole = createRole("SUPER_ADMIN");
        grantAuditRead(superAdminRole);
        userRoles.saveAndFlush(new UserRole(superAdmin, superAdminRole, null, null));

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject(caller.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + superAdmin.getId()
                                + "\",\"role\":\"SUPER_ADMIN\",\"globalAccess\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingInvalidAndMalformedSubjectAuthenticationAreDenied() throws Exception {
        mockMvc.perform(post(AUTHORIZATION_PATH))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(AUTHORIZATION_PATH).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(AUTHORIZATION_PATH)
                        .with(jwt().jwt(token -> token.subject("not-a-uuid"))))
                .andExpect(status().isForbidden());
    }

    private AppUser createUser(UserStatus status) {
        return users.saveAndFlush(new AppUser(
                UUID.randomUUID() + "@rra.rw", "Audit authorization test", status));
    }

    private Role createRole(String code) {
        return roles.saveAndFlush(new Role(code, code, true));
    }

    private void grantAuditRead(Role role) {
        Permission permission = permissions.findByCodeIgnoreCase(IdentityPermissionCodes.AUDIT_READ)
                .orElseThrow();
        rolePermissions.saveAndFlush(new RolePermission(role, permission));
    }
}
