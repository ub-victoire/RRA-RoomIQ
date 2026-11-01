package rw.rra.roomiq.identity.api;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.Permission;
import rw.rra.roomiq.identity.domain.entity.Role;
import rw.rra.roomiq.identity.domain.entity.RolePermission;
import rw.rra.roomiq.identity.domain.entity.UserRole;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.entity.UserPrivilege;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;
import rw.rra.roomiq.identity.domain.repository.IdentityAuditOutboxRepository;
import rw.rra.roomiq.identity.domain.repository.PermissionRepository;
import rw.rra.roomiq.identity.domain.repository.RolePermissionRepository;
import rw.rra.roomiq.identity.domain.repository.RoleRepository;
import rw.rra.roomiq.identity.domain.repository.UserRoleRepository;
import rw.rra.roomiq.identity.domain.repository.UserPrivilegeRepository;
import rw.rra.roomiq.identity.domain.security.IdentityAuthorizationService;
import rw.rra.roomiq.identity.domain.service.RolePermissionManagementService;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity-authorization-test",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "roomiq.auth.jwt-secret=test-only-signing-key-at-least-32-bytes-long",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.flyway.clean-disabled=false")
class IdentityAuthorizationTests {
    private static final String USER_READ = "IDENTITY_USER_READ";
    private static final String USER_MANAGE = "IDENTITY_USER_MANAGE";
    private static final String USER_ROLE_ASSIGN = "IDENTITY_USER_ROLE_ASSIGN";
        private static final String USER_ROLE_READ = "IDENTITY_USER_ROLE_READ";
    private static final String PRIVILEGE_GRANT = "IDENTITY_PRIVILEGE_GRANT";
        private static final String PRIVILEGE_READ = "IDENTITY_PRIVILEGE_READ";
    private static final String SYSTEM_ADMIN = "IDENTITY_SYSTEM_ADMIN";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository users;

        @Autowired
        private IdentityAuditOutboxRepository auditOutboxRepository;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Autowired
    private RolePermissionRepository rolePermissions;

    @Autowired
    private UserRoleRepository userRoles;

        @Autowired
        private UserPrivilegeRepository userPrivileges;

        @Autowired
        private IdentityAuthorizationService policy;

        @Autowired
        private RolePermissionManagementService rolePermissionManagement;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void scopedAdminAndSuperAdminPoliciesEnforcePermissionsAndBuildingBoundaries() throws Exception {
        UUID authorizedBuilding = UUID.randomUUID();
        UUID otherBuilding = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Role adminRole = roles.saveAndFlush(new Role("ADMIN", "Scoped admin", true));
        Role secretaryRole = roles.saveAndFlush(new Role("SECRETARY", "Secretary", true));
        Role superRole = roles.saveAndFlush(new Role("SUPER_ADMIN", "Super Admin", true));
        assignPermissions(adminRole, List.of(USER_READ, USER_MANAGE, USER_ROLE_ASSIGN, USER_ROLE_READ,
                PRIVILEGE_GRANT, PRIVILEGE_READ,
                "BOOKING_DIRECT_CREATE", "BOOKING_APPROVE", "BOOKING_CANCEL", "BOOKING_EXTENSION_DECIDE", "ROOM_MANAGE"));
        assignPermissions(secretaryRole, List.of("BOOKING_REQUEST_CREATE", "BOOKING_CANCEL_OWN", "BOOKING_EXTENSION_REQUEST"));
        assignPermissions(superRole, List.of(SYSTEM_ADMIN));

        AppUser admin = createUser("admin-" + suffix + "@rra.rw", authorizedBuilding);
        AppUser secretary = createUser("secretary-" + suffix + "@rra.rw", authorizedBuilding);
        UUID departmentId = UUID.randomUUID();
        secretary.updateProfile(null, null, null, null, null, departmentId, authorizedBuilding);
        secretary = users.saveAndFlush(secretary);
        AppUser inScopeUser = createUser("in-scope-" + suffix + "@rra.rw", authorizedBuilding);
        inScopeUser.updateStatus(UserStatus.PENDING);
        inScopeUser = users.saveAndFlush(inScopeUser);
        AppUser privilegeTarget = createUser("privilege-target-" + suffix + "@rra.rw", authorizedBuilding);
        AppUser outOfScopeUser = createUser("out-scope-" + suffix + "@rra.rw", otherBuilding);
        AppUser superAdmin = createUser("super-" + suffix + "@rra.rw", null);
        userRoles.saveAndFlush(new UserRole(admin, adminRole, authorizedBuilding, superAdmin));
        userRoles.saveAndFlush(new UserRole(secretary, secretaryRole, authorizedBuilding, superAdmin));
        userRoles.saveAndFlush(new UserRole(privilegeTarget, secretaryRole, authorizedBuilding, superAdmin));
        userRoles.saveAndFlush(new UserRole(superAdmin, superRole, null, null));

        String adminAccess = login(admin.getEmail());
        String secretaryAccess = login(secretary.getEmail());
        String systemAccess = login(superAdmin.getEmail());

        var secretaryPrincipal = UsernamePasswordAuthenticationToken.authenticated(secretary.getEmail(), "test", List.of());
        var adminPrincipal = UsernamePasswordAuthenticationToken.authenticated(admin.getEmail(), "test", List.of());
        assertThat(policy.canRequestBooking(secretaryPrincipal, secretary.getId(), departmentId,
                authorizedBuilding, false, Instant.now())).isTrue();
        assertThat(policy.canRequestBooking(secretaryPrincipal, secretary.getId(), departmentId,
                authorizedBuilding, true, Instant.now())).isFalse();
        assertThat(policy.canRequestBooking(secretaryPrincipal, inScopeUser.getId(), departmentId,
                authorizedBuilding, false, Instant.now())).isFalse();
        Instant privilegeStart = Instant.now().minusSeconds(5);
        Instant privilegeEnd = Instant.now().plusSeconds(60);
        userPrivileges.saveAndFlush(new UserPrivilege(secretary, "CG_BOOKING", admin,
                privilegeStart, privilegeEnd, true));
        assertThat(policy.canRequestBooking(secretaryPrincipal, secretary.getId(), departmentId,
                authorizedBuilding, true, Instant.now())).isTrue();
        assertThat(policy.canRequestBooking(secretaryPrincipal, secretary.getId(), departmentId,
                otherBuilding, false, Instant.now())).isFalse();
        assertThat(policy.canApproveBooking(adminPrincipal, secretary.getId(), authorizedBuilding)).isTrue();
        assertThat(policy.canApproveBooking(adminPrincipal, admin.getId(), authorizedBuilding)).isFalse();
        assertThat(policy.canApproveBooking(adminPrincipal, secretary.getId(), otherBuilding)).isFalse();
        assertThat(policy.canDirectBook(adminPrincipal, authorizedBuilding)).isTrue();
        assertThat(policy.canDirectBook(adminPrincipal, otherBuilding)).isFalse();
        assertThat(policy.canManageRooms(adminPrincipal, authorizedBuilding)).isTrue();
        assertThat(policy.canManageRooms(adminPrincipal, otherBuilding)).isFalse();

        mockMvc.perform(post("/api/v1/internal/authorization/room-management")
                        .header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officeBuildingId\":\"" + authorizedBuilding + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUserId").value(admin.getId().toString()));
        mockMvc.perform(post("/api/v1/internal/authorization/room-management")
                        .header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officeBuildingId\":\"" + otherBuilding + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/internal/authorization/room-management")
                        .header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officeBuildingId\":null}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/internal/authorization/room-management")
                        .header("Authorization", bearer(systemAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officeBuildingId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorUserId").value(superAdmin.getId().toString()));

        mockMvc.perform(get("/api/v1/users/{id}", secretary.getId())
                        .header("Authorization", bearer(secretaryAccess)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", inScopeUser.getId())
                        .header("Authorization", bearer(secretaryAccess)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/roles").with(csrf())
                        .header("Authorization", bearer(secretaryAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SECRETARY_ROLE\",\"name\":\"No\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users/{id}", inScopeUser.getId()).header("Authorization", bearer(adminAccess)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{id}", outOfScopeUser.getId())
                        .header("Authorization", bearer(adminAccess))
                        .header("X-Correlation-ID", "cross-scope-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        String denialAudit = auditOutboxRepository
                .findTopByEventTypeOrderByCreatedAtDesc("AUTHORIZATION_DENIED").orElseThrow().getPayload();
        assertThat(denialAudit).contains("AUTHORIZATION_DENIED", admin.getId().toString(),
                "cross-scope-denied", "\"schemaVersion\":1")
                .doesNotContain(admin.getEmail(), outOfScopeUser.getEmail());

        var userListResult = mockMvc.perform(get("/api/v1/users").queryParam("officeBuildingId", authorizedBuilding.toString())
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.users[*].id").isArray())
                .andReturn();
        List<String> visibleUsers = JsonPath.read(userListResult.getResponse().getContentAsString(), "$.data.users[*].id");
        assertThat(visibleUsers).contains(inScopeUser.getId().toString(), secretary.getId().toString())
                .doesNotContain(admin.getId().toString(), superAdmin.getId().toString());
        mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(adminAccess)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/{userId}/roles", inScopeUser.getId())
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/{userId}/roles", outOfScopeUser.getId())
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isForbidden());

        String sameScopeCreate = "{\"email\":\"new-" + suffix + "@rra.rw\",\"fullName\":\"New User\","
                + "\"officeBuildingId\":\"" + authorizedBuilding + "\"}";
        mockMvc.perform(post("/api/v1/users").with(csrf()).header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON).content(sameScopeCreate))
                .andExpect(status().isCreated());
        String otherScopeCreate = "{\"email\":\"other-" + suffix + "@rra.rw\",\"fullName\":\"Other User\","
                + "\"officeBuildingId\":\"" + otherBuilding + "\"}";
        mockMvc.perform(post("/api/v1/users").with(csrf()).header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON).content(otherScopeCreate))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/users/{id}", inScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Scoped update\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/{id}", outOfScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Forbidden update\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/users/{id}", inScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"officeBuildingId\":\"" + otherBuilding + "\"}"))
                .andExpect(status().isForbidden());

        String assignSecretary = "{\"roleId\":\"" + secretaryRole.getId() + "\","
                + "\"scopeOfficeBuildingId\":\"" + authorizedBuilding + "\"}";
        mockMvc.perform(post("/api/v1/users/{id}/roles", inScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content(assignSecretary))
                .andExpect(status().isCreated());
        String assignAdmin = "{\"roleId\":\"" + adminRole.getId() + "\","
                + "\"scopeOfficeBuildingId\":\"" + authorizedBuilding + "\"}";
        mockMvc.perform(post("/api/v1/users/{id}/roles", inScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content(assignAdmin))
                .andExpect(status().isForbidden());
        UserRole protectedAdminAssignment = userRoles.saveAndFlush(
                new UserRole(inScopeUser, adminRole, authorizedBuilding, superAdmin));
        mockMvc.perform(delete("/api/v1/users/{userId}/roles/{assignmentId}",
                                inScopeUser.getId(), protectedAdminAssignment.getId())
                        .with(csrf()).header("Authorization", bearer(adminAccess)))
                .andExpect(status().isForbidden());

        String grantPrivilege = "{\"privilegeCode\":\"CG_BOOKING\",\"grantedByUserId\":\""
                + superAdmin.getId() + "\"}";
        mockMvc.perform(post("/api/v1/users/{id}/privileges", privilegeTarget.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content(grantPrivilege))
                .andExpect(status().isCreated());
        UserPrivilege scopedGrant = userPrivileges.findAllByUserIdAndPrivilegeCodeAndActiveTrue(
                privilegeTarget.getId(), "CG_BOOKING").getFirst();
        assertThat(scopedGrant.getGrantedBy().getId()).isEqualTo(admin.getId());
        mockMvc.perform(post("/api/v1/users/{id}/privileges", privilegeTarget.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privilegeCode\":\"ADMIN_OVERRIDE\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/users/{id}/privileges", outOfScopeUser.getId()).with(csrf())
                        .header("Authorization", bearer(adminAccess)).contentType(MediaType.APPLICATION_JSON)
                        .content(grantPrivilege))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/roles").with(csrf()).header("Authorization", bearer(adminAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"UNAUTHORIZED_ROLE\",\"name\":\"No\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/roles").with(csrf()).header("Authorization", bearer(systemAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"GLOBAL_" + suffix.toUpperCase() + "\",\"name\":\"Global role\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/api/v1/users/{userId}/roles/{assignmentId}",
                                inScopeUser.getId(), protectedAdminAssignment.getId())
                        .with(csrf()).header("Authorization", bearer(systemAccess)))
                .andExpect(status().isOk());
        rolePermissionManagement.revokePermission(adminRole.getId(),
                permissions.findByCodeIgnoreCase(USER_READ).orElseThrow().getId());
        mockMvc.perform(get("/api/v1/users/{id}", inScopeUser.getId())
                        .header("Authorization", bearer(adminAccess)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/{id}", outOfScopeUser.getId())
                        .header("Authorization", bearer(systemAccess)))
                .andExpect(status().isOk());
    }

    @Test
    void notificationRoutingRequiresAnActiveAuthorizedSecretaryAndCurrentIdentityScope() {
        UUID buildingId = UUID.randomUUID();
        UUID departmentId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Role secretaryRole = roles.findByCodeIgnoreCase("SECRETARY")
                .orElseGet(() -> roles.saveAndFlush(new Role("SECRETARY", "Secretary", true)));
        for (String code : List.of("BOOKING_REQUEST_CREATE", "BOOKING_EXTENSION_REQUEST")) {
            Permission permission = permissions.findByCodeIgnoreCase(code)
                    .orElseGet(() -> permissions.saveAndFlush(new Permission(code, code)));
            if (!rolePermissions.existsByRoleIdAndPermissionId(secretaryRole.getId(), permission.getId())) {
                rolePermissions.saveAndFlush(new RolePermission(secretaryRole, permission));
            }
        }
        AppUser secretary = createUser("notification-secretary-" + suffix + "@rra.rw", buildingId);
        secretary.updateProfile(null, null, null, null, null, departmentId, buildingId);
        secretary = users.saveAndFlush(secretary);
        userRoles.saveAndFlush(new UserRole(secretary, secretaryRole, buildingId, null));

        assertThat(policy.authorizedNotificationBookingRequestBuilding(
                secretary.getId(), departmentId, false)).isEqualTo(buildingId);
        assertThat(policy.authorizedNotificationBookingRequestBuilding(
                secretary.getId(), UUID.randomUUID(), false)).isNull();
        assertThat(policy.authorizedNotificationBookingRequestBuilding(
                secretary.getId(), departmentId, true)).isNull();
        assertThat(policy.authorizedNotificationExtensionRequestBuilding(secretary.getId()))
                .isEqualTo(buildingId);

        secretary.updateStatus(UserStatus.DISABLED);
        secretary = users.saveAndFlush(secretary);
        assertThat(policy.authorizedNotificationBookingRequestBuilding(
                secretary.getId(), departmentId, false)).isNull();
        assertThat(policy.authorizedNotificationExtensionRequestBuilding(secretary.getId())).isNull();
    }

    private AppUser createUser(String email, UUID buildingId) {
        AppUser user = new AppUser(email, "Authorization test", UserStatus.ACTIVE);
        user.replacePasswordHash(passwordEncoder.encode("Good-password-9"));
        user.updateProfile(null, null, null, null, null, null, buildingId);
        return users.saveAndFlush(user);
    }

    private void assignPermissions(Role role, List<String> codes) {
        for (String code : codes) {
            Permission permission = permissions.findByCodeIgnoreCase(code)
                    .orElseGet(() -> permissions.saveAndFlush(new Permission(code, code)));
            rolePermissions.saveAndFlush(new RolePermission(role, permission));
        }
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Good-password-9\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
