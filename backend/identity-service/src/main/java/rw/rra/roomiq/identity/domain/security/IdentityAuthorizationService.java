package rw.rra.roomiq.identity.domain.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.rra.roomiq.identity.domain.entity.AppUser;
import rw.rra.roomiq.identity.domain.entity.Role;
import rw.rra.roomiq.identity.domain.entity.UserStatus;
import rw.rra.roomiq.identity.domain.dto.AuditSearchAuthorizationResponse;
import rw.rra.roomiq.identity.domain.dto.MeetingAuthorizationRequest.Visibility;
import rw.rra.roomiq.identity.domain.repository.AppUserRepository;
import rw.rra.roomiq.identity.domain.repository.IdentityAuthorizationRepository;
import rw.rra.roomiq.identity.domain.repository.RoleRepository;
import rw.rra.roomiq.identity.domain.repository.UserPrivilegeRepository;
import rw.rra.roomiq.identity.domain.repository.UserRoleRepository;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.PRIVILEGE_GRANT;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.PRIVILEGE_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.PERMISSION_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.ROLE_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.SYSTEM_ADMIN;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.USER_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.USER_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.USER_ROLE_ASSIGN;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.USER_ROLE_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_APPROVE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_CANCEL;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_CANCEL_OWN;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_DIRECT_CREATE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_EXTENSION_DECIDE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_EXTENSION_REQUEST;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_LIFECYCLE_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_LIFECYCLE_OWN;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.BOOKING_REQUEST_CREATE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.ROOM_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.NOTIFICATION_TEMPLATE_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.NOTIFICATION_DELIVERY_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.AUDIT_READ;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.RETENTION_POLICY_MANAGE;
import static rw.rra.roomiq.identity.domain.security.IdentityPermissionCodes.LEGAL_HOLD_MANAGE;

@Service("identityAuthorization")
@Transactional(readOnly = true)
public class IdentityAuthorizationService {
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final IdentityAuthorizationRepository authorization;
    private final UserPrivilegeRepository privileges;
    private final UserRoleRepository userRoles;

    public IdentityAuthorizationService(AppUserRepository users, RoleRepository roles,
                                        IdentityAuthorizationRepository authorization,
                                        UserPrivilegeRepository privileges,
                                        UserRoleRepository userRoles) {
        this.users = users;
        this.roles = roles;
        this.authorization = authorization;
        this.privileges = privileges;
        this.userRoles = userRoles;
    }

    public boolean canListUsers(Authentication authentication, UUID requestedBuildingId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        return requestedBuildingId != null
                && hasPermission(authentication, actorId, USER_READ, requestedBuildingId);
    }

    public boolean canReadUser(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (actorId.equals(targetUserId) || hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
            && isSecretaryOrUnassignedPending(target)
                && hasPermission(authentication, actorId, USER_READ, target.getOfficeBuildingId());
    }

    public boolean canManageUser(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
            && isSecretaryOrUnassignedPending(target)
                && hasPermission(authentication, actorId, USER_MANAGE, target.getOfficeBuildingId());
    }

    public boolean canCreateUser(Authentication authentication, UUID buildingId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId)
                && (hasGlobalPermission(authentication, actorId)
                || (buildingId != null && hasPermission(authentication, actorId, USER_MANAGE, buildingId)));
    }

    public boolean canUpdateUser(Authentication authentication, UUID targetUserId,
                                 UUID requestedDepartmentId, UUID requestedOfficeBuildingId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser user = users.findById(targetUserId).orElse(null);
        if (user == null) {
            return false;
        }
        boolean departmentUnchanged = requestedDepartmentId == null
                || requestedDepartmentId.equals(user.getDepartmentId());
        boolean officeUnchanged = requestedOfficeBuildingId == null
                || requestedOfficeBuildingId.equals(user.getOfficeBuildingId());

        if (actorId.equals(targetUserId)) {
            return departmentUnchanged && officeUnchanged;
        }

        if (!isSecretaryOrUnassignedPending(user)) {
            return false;
        }

        UUID currentBuilding = user.getOfficeBuildingId();
        if (currentBuilding == null || !hasPermission(authentication, actorId, USER_MANAGE, currentBuilding)) {
            return false;
        }
        boolean destinationInScope = requestedOfficeBuildingId == null
                || requestedOfficeBuildingId.equals(currentBuilding)
                || hasPermission(authentication, actorId, USER_MANAGE, requestedOfficeBuildingId);
        return destinationInScope && departmentUnchanged;
    }

    public boolean canManageDefinitions(Authentication authentication) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && hasGlobalPermission(authentication, actorId);
    }

    public boolean isSystemAdministrator(Authentication authentication) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && hasGlobalPermission(authentication, actorId);
    }

    public boolean canReadOrganization(Authentication authentication) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId);
    }

    public boolean canManageOrganization(Authentication authentication) {
        return isSystemAdministrator(authentication);
    }

    public boolean canReadScheduling(Authentication authentication) {
        return canReadOrganization(authentication);
    }

    public boolean canManageScheduling(Authentication authentication) {
        return isSystemAdministrator(authentication);
    }

    public boolean canAssignRole(Authentication authentication, UUID targetUserId, UUID roleId,
                                 UUID requestedBuildingId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        Role role = roles.findById(roleId).orElse(null);
        AppUser target = users.findById(targetUserId).orElse(null);
        if (role == null || target == null) {
            return false;
        }
        boolean globalAdmin = hasGlobalPermission(authentication, actorId);
        if (isPrivilegedRole(role) && !globalAdmin) {
            return false;
        }
        if (globalAdmin) {
            return true;
        }
        if (!isSecretaryOrUnassignedPending(target)) {
            return false;
        }
        if (userRoles.findAllByUserId(targetUserId).stream()
                .anyMatch(assignment -> isPrivilegedRole(assignment.getRole()))) {
            return false;
        }
        if (!role.getCode().equals("SECRETARY")) {
            return false;
        }
        return requestedBuildingId != null
                && requestedBuildingId.equals(target.getOfficeBuildingId())
                && hasPermission(authentication, actorId, USER_ROLE_ASSIGN, requestedBuildingId);
    }

    public boolean canReadUserRoles(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (actorId.equals(targetUserId) || hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
                && isSecretaryOrUnassignedPending(target)
                && hasPermission(authentication, actorId, USER_ROLE_READ, target.getOfficeBuildingId());
    }

    public boolean canManageUserRoles(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
                && isSecretaryOrUnassignedPending(target)
                && hasPermission(authentication, actorId, USER_ROLE_ASSIGN, target.getOfficeBuildingId());
    }

    public boolean canAssignUserRole(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
                && hasPermission(authentication, actorId, USER_ROLE_ASSIGN, target.getOfficeBuildingId());
    }

    public boolean canRevokeUserRole(Authentication authentication, UUID targetUserId, UUID assignmentId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        var assignment = userRoles.findByIdAndUserId(assignmentId, targetUserId).orElse(null);
        AppUser target = users.findById(targetUserId).orElse(null);
        if (assignment == null || target == null || target.getOfficeBuildingId() == null
                || !assignment.getRole().getCode().equals("SECRETARY")
                || !isSecretaryOrUnassignedPending(target)) {
            return false;
        }
        return hasPermission(authentication, actorId, USER_ROLE_ASSIGN, target.getOfficeBuildingId());
    }

    public boolean canReadRoleDefinitions(Authentication authentication) {
        return canReadGlobalDefinitions(authentication, ROLE_READ);
    }

    public boolean canReadPermissionDefinitions(Authentication authentication) {
        return canReadGlobalDefinitions(authentication, PERMISSION_READ);
    }

    public boolean canManagePrivilege(Authentication authentication, UUID targetUserId, String privilegeCode) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
            && isSecretaryOrUnassignedPending(target)
                && "CG_BOOKING".equals(privilegeCode)
                && hasPermission(authentication, actorId, PRIVILEGE_GRANT, target.getOfficeBuildingId());
    }

    public boolean canRevokePrivilege(Authentication authentication, UUID targetUserId, UUID privilegeId) {
        var privilege = privileges.findById(privilegeId).orElse(null);
        return privilege != null && privilege.getUser().getId().equals(targetUserId)
                && canManagePrivilege(authentication, targetUserId, privilege.getPrivilegeCode());
    }

    public boolean canReadPrivileges(Authentication authentication, UUID targetUserId) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (actorId.equals(targetUserId) || hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        AppUser target = users.findById(targetUserId).orElse(null);
        return target != null && target.getOfficeBuildingId() != null
            && isSecretaryOrUnassignedPending(target)
            && hasPermission(authentication, actorId, PRIVILEGE_READ, target.getOfficeBuildingId());
    }

    public boolean canRequestBooking(Authentication authentication, UUID requestedByUserId,
                                     UUID departmentId, UUID buildingId, boolean vipRoom, Instant at) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !actorId.equals(requestedByUserId) || buildingId == null || !isActive(actorId)) {
            return false;
        }
        AppUser user = users.findById(actorId).orElse(null);
        if (user == null || !buildingId.equals(user.getOfficeBuildingId())
                || departmentId == null || !departmentId.equals(user.getDepartmentId())) {
            return false;
        }
        boolean globallyAuthorized = hasGlobalPermission(authentication, actorId);
        if (!globallyAuthorized && !hasPermission(authentication, actorId, BOOKING_REQUEST_CREATE, buildingId)) {
            return false;
        }
        return !vipRoom || globallyAuthorized
                || hasActivePrivilege(actorId, "CG_BOOKING", at == null ? Instant.now() : at);
    }

    public boolean canAuthenticateBooking(Authentication authentication) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId);
    }

    public boolean canListBookingRequests(Authentication authentication, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId)) {
            return false;
        }
        if (buildingId != null) {
            return hasGlobalPermission(authentication, actorId)
                    || hasPermission(authentication, actorId, BOOKING_APPROVE, buildingId);
        }
        AppUser user = users.findById(actorId).orElse(null);
        if (user == null || user.getOfficeBuildingId() == null) {
            return false;
        }
        return hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_REQUEST_CREATE, user.getOfficeBuildingId())
                || hasPermission(authentication, actorId, BOOKING_DIRECT_CREATE, user.getOfficeBuildingId());
    }

    public boolean canReadBookingRequest(Authentication authentication, UUID requesterUserId, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || requesterUserId == null || buildingId == null) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return true;
        }
        if (actorId.equals(requesterUserId)) {
            AppUser user = users.findById(actorId).orElse(null);
            return user != null && buildingId.equals(user.getOfficeBuildingId())
                    && (hasPermission(authentication, actorId, BOOKING_REQUEST_CREATE, buildingId)
                    || hasPermission(authentication, actorId, BOOKING_DIRECT_CREATE, buildingId));
        }
        return hasPermission(authentication, actorId, BOOKING_APPROVE, buildingId);
    }

    public boolean canRequestBooking(Authentication authentication, UUID departmentId,
                                     UUID buildingId, boolean vipRoom) {
        UUID actorId = actorId(authentication);
        return actorId != null && canRequestBooking(authentication, actorId,
                departmentId, buildingId, vipRoom, Instant.now());
    }

    public boolean canSubmitBookingRequest(Authentication authentication, UUID requesterUserId,
                                           UUID departmentId, UUID buildingId, boolean vipRoom) {
        UUID actorId = actorId(authentication);
        return actorId != null && actorId.equals(requesterUserId)
                && canRequestBooking(authentication, requesterUserId, departmentId, buildingId,
                vipRoom, Instant.now());
    }

    public UUID authorizedNotificationBookingRequestBuilding(UUID requesterUserId, UUID departmentId,
                                                              Boolean vipRoom) {
        AppUser requester = activeSecretary(requesterUserId);
        if (requester == null || departmentId == null || !departmentId.equals(requester.getDepartmentId())
                || !authorization.hasPermissionAtBuilding(requesterUserId, BOOKING_REQUEST_CREATE,
                requester.getOfficeBuildingId())
                || Boolean.TRUE.equals(vipRoom)
                && !hasActivePrivilege(requesterUserId, "CG_BOOKING", Instant.now())) {
            return null;
        }
        return requester.getOfficeBuildingId();
    }

    public UUID authorizedNotificationExtensionRequestBuilding(UUID requesterUserId) {
        AppUser requester = activeSecretary(requesterUserId);
        if (requester == null || !authorization.hasPermissionAtBuilding(
                requesterUserId, BOOKING_EXTENSION_REQUEST, requester.getOfficeBuildingId())) {
            return null;
        }
        return requester.getOfficeBuildingId();
    }

    public boolean canApproveBooking(Authentication authentication, UUID requesterUserId, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || actorId.equals(requesterUserId) || buildingId == null) {
            return false;
        }
        return hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_APPROVE, buildingId);
    }

    public String bookingRequesterDisplayName(UUID requesterUserId) {
        if (requesterUserId == null) {
            return null;
        }
        AppUser requester = users.findById(requesterUserId).orElse(null);
        if (requester == null || requester.getStatus() != UserStatus.ACTIVE) {
            return null;
        }
        String displayName = requester.getDisplayName();
        return displayName == null || displayName.isBlank() ? requester.getFullName() : displayName;
    }

    public boolean canDirectBook(Authentication authentication, UUID buildingId) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && buildingId != null
                && (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_DIRECT_CREATE, buildingId));
    }

    public BookingDirectBookingAuthority directBookingAuthority(Authentication authentication,
                                                                 UUID departmentId, UUID buildingId,
                                                                 boolean vipRoom) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || buildingId == null) {
            return null;
        }
        if (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_DIRECT_CREATE, buildingId)) {
            return BookingDirectBookingAuthority.ADMIN;
        }
        return canRequestBooking(authentication, departmentId, buildingId, vipRoom)
                ? BookingDirectBookingAuthority.SECRETARY : null;
    }

    public boolean canCancelBooking(Authentication authentication, UUID organizerUserId, UUID buildingId) {
        return cancellationAuthority(authentication, organizerUserId, buildingId) != null;
    }

    public rw.rra.roomiq.identity.domain.dto.BookingAuthorizationResponse.CancellationAuthority cancellationAuthority(
            Authentication authentication, UUID organizerUserId, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || buildingId == null) {
            return null;
        }
        if (hasGlobalPermission(authentication, actorId)) {
            return rw.rra.roomiq.identity.domain.dto.BookingAuthorizationResponse.CancellationAuthority.SYSTEM_ADMIN;
        }
        if (actorId.equals(organizerUserId)) {
            AppUser actor = users.findById(actorId).orElse(null);
            if (actor == null || !buildingId.equals(actor.getOfficeBuildingId())
                    || !hasPermission(authentication, actorId, BOOKING_CANCEL_OWN, buildingId)) {
                return null;
            }
            return rw.rra.roomiq.identity.domain.dto.BookingAuthorizationResponse.CancellationAuthority.OWNER;
        }
        return hasPermission(authentication, actorId, BOOKING_CANCEL, buildingId)
                ? rw.rra.roomiq.identity.domain.dto.BookingAuthorizationResponse.CancellationAuthority.ADMIN : null;
    }

    public boolean canManageBookingLifecycle(Authentication authentication, UUID organizerUserId,
                                              UUID departmentId, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || organizerUserId == null || departmentId == null
                || buildingId == null) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_LIFECYCLE_MANAGE, buildingId)) {
            return true;
        }
        if (!actorId.equals(organizerUserId)) {
            return false;
        }
        AppUser user = users.findById(actorId).orElse(null);
        return user != null && departmentId.equals(user.getDepartmentId())
                && buildingId.equals(user.getOfficeBuildingId())
                && hasPermission(authentication, actorId, BOOKING_LIFECYCLE_OWN, buildingId);
    }

    public boolean canReadMeeting(Authentication authentication, UUID organizerUserId,
                                  UUID departmentId, UUID buildingId, Visibility visibility) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || organizerUserId == null || departmentId == null
                || buildingId == null || visibility == null) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_APPROVE, buildingId)) {
            return true;
        }
        AppUser actor = users.findById(actorId).orElse(null);
        if (actor == null || !buildingId.equals(actor.getOfficeBuildingId())) {
            return false;
        }
        if (actorId.equals(organizerUserId)) {
            return departmentId.equals(actor.getDepartmentId());
        }
        return visibility == Visibility.INTERNAL;
    }

    public boolean canManageMeeting(Authentication authentication, UUID organizerUserId,
                                   UUID departmentId, UUID buildingId) {
        UUID actorId = actorId(authentication);
        if (actorId == null || !isActive(actorId) || organizerUserId == null || departmentId == null
                || buildingId == null) {
            return false;
        }
        if (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_APPROVE, buildingId)) {
            return true;
        }
        if (!actorId.equals(organizerUserId)) {
            return false;
        }
        AppUser organizer = users.findById(actorId).orElse(null);
        return organizer != null && buildingId.equals(organizer.getOfficeBuildingId())
                && departmentId.equals(organizer.getDepartmentId());
    }

    public String activeMeetingParticipantDisplayName(UUID participantUserId, UUID buildingId) {
        if (participantUserId == null || buildingId == null) {
            return null;
        }
        AppUser participant = users.findById(participantUserId).orElse(null);
        if (participant == null || participant.getStatus() != UserStatus.ACTIVE
                || !buildingId.equals(participant.getOfficeBuildingId())) {
            return null;
        }
        String displayName = participant.getDisplayName();
        return displayName == null || displayName.isBlank() ? participant.getFullName() : displayName;
    }

    public boolean canRequestBookingExtension(Authentication authentication, UUID requesterUserId,
                                              UUID buildingId) {
        UUID actorId = actorId(authentication);
        return actorId != null && actorId.equals(requesterUserId) && isActive(actorId)
                && buildingId != null && hasPermission(authentication, actorId, BOOKING_EXTENSION_REQUEST, buildingId);
    }

    public boolean canDecideBookingExtension(Authentication authentication, UUID requesterUserId,
                                             UUID buildingId) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && !actorId.equals(requesterUserId) && buildingId != null
                && (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, BOOKING_EXTENSION_DECIDE, buildingId));
    }

    public boolean canManageRooms(Authentication authentication, UUID buildingId) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && buildingId != null
                && (hasGlobalPermission(authentication, actorId)
                || hasPermission(authentication, actorId, ROOM_MANAGE, buildingId));
    }

    public boolean canManageRoomCatalog(Authentication authentication) {
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId) && hasGlobalPermission(authentication, actorId);
    }

    private boolean hasGlobalPermission(Authentication authentication, UUID actorId) {
        return hasExplicitAuthority(authentication, SYSTEM_ADMIN)
                || authorization.hasPermissionAtBuilding(actorId, SYSTEM_ADMIN, null);
    }

    private boolean canReadGlobalDefinitions(Authentication authentication, String permissionCode) {
        if (hasExplicitAuthority(authentication, SYSTEM_ADMIN)) {
            return true;
        }
        UUID actorId = actorId(authentication);
        return actorId != null && isActive(actorId)
                && (hasGlobalPermission(authentication, actorId)
                || !authorization.findAuthorizedBuildingScopes(actorId, permissionCode).isEmpty());
    }

    private boolean hasPermission(Authentication authentication, UUID actorId, String permissionCode,
                                  UUID buildingId) {
        return hasExplicitAuthority(authentication, permissionCode)
                || hasGlobalPermission(authentication, actorId)
                || authorization.hasPermissionAtBuilding(actorId, permissionCode, buildingId);
    }

    private boolean hasExplicitAuthority(Authentication authentication, String permissionCode) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(permissionCode::equals);
    }

    private UUID actorId(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            try {
                return UUID.fromString(jwtAuthentication.getToken().getSubject());
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                return users.findByEmailIgnoreCase(authentication.getName())
                        .map(AppUser::getId).orElse(null);
            } catch (RuntimeException exception) {
                return null;
            }
        }
        return null;
    }

    public UUID authenticatedUserId(Authentication authentication) {
        return actorId(authentication);
    }

    public boolean canManageNotificationTemplates(Authentication authentication) {
        UUID userId = actorId(authentication);
        return userId != null && authorization.isActiveSuperAdminWithPermission(userId,
                UserStatus.ACTIVE, NOTIFICATION_TEMPLATE_MANAGE);
    }

    public boolean canManageNotificationDelivery(Authentication authentication) {
        UUID userId = actorId(authentication);
        return userId != null && authorization.isActiveSuperAdminWithPermission(userId,
                UserStatus.ACTIVE, NOTIFICATION_DELIVERY_MANAGE);
    }

    public boolean canManageRetentionPolicies(Authentication authentication) {
        UUID userId = actorId(authentication);
        return userId != null && authorization.isActiveSuperAdminWithPermission(userId,
                UserStatus.ACTIVE, RETENTION_POLICY_MANAGE);
    }

    public boolean canManageLegalHolds(Authentication authentication) {
        UUID userId = actorId(authentication);
        return userId != null && authorization.isActiveSuperAdminWithPermission(userId,
                UserStatus.ACTIVE, LEGAL_HOLD_MANAGE);
    }

    public Optional<AuditSearchAuthorizationResponse> auditSearchAuthorization(
            Authentication authentication) {
        UUID userId = authenticatedJwtUserId(authentication);
        if (userId == null) {
            return Optional.empty();
        }

        if (authorization.isActiveSuperAdminWithPermission(userId, UserStatus.ACTIVE, AUDIT_READ)) {
            return Optional.of(new AuditSearchAuthorizationResponse(userId,
                    AuditSearchAuthorizationResponse.AccessScope.GLOBAL, List.of()));
        }

        List<UUID> buildingIds = authorization.findActiveAdminAuditBuildingScopes(
                userId, UserStatus.ACTIVE, AUDIT_READ);
        if (buildingIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuditSearchAuthorizationResponse(userId,
                AuditSearchAuthorizationResponse.AccessScope.BUILDING_SCOPED, buildingIds));
    }

    private UUID authenticatedJwtUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !jwtAuthentication.isAuthenticated()) {
            return null;
        }
        String subject = jwtAuthentication.getToken().getSubject();
        if (subject == null) {
            return null;
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isActive(UUID userId) {
        return users.findById(userId).map(user -> user.getStatus().name().equals("ACTIVE")).orElse(false);
    }

    private AppUser activeSecretary(UUID userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getOfficeBuildingId() == null) {
            return null;
        }
        boolean assignedSecretary = userRoles.findAllByUserIdAndScopeOfficeBuildingId(
                        userId, user.getOfficeBuildingId()).stream()
                .anyMatch(assignment -> assignment.getRole().getCode().equals("SECRETARY"));
        return assignedSecretary ? user : null;
    }

    private boolean hasActivePrivilege(UUID userId, String privilegeCode, Instant at) {
        return privileges.findAllByUserIdAndPrivilegeCodeAndActiveTrue(userId, privilegeCode).stream()
                .anyMatch(privilege -> privilege.isEffectiveAt(at));
    }

    private boolean isPrivilegedRole(Role role) {
        return role.getCode().equals("ADMIN") || role.getCode().equals("SUPER_ADMIN");
    }

    private boolean isSecretaryOrUnassignedPending(AppUser user) {
        var assignments = userRoles.findAllByUserId(user.getId());
        if (assignments.isEmpty()) {
            return user.getStatus().name().equals("PENDING");
        }
        if (assignments.stream().anyMatch(assignment -> isPrivilegedRole(assignment.getRole()))) {
            return false;
        }
        UUID officeBuildingId = user.getOfficeBuildingId();
        return officeBuildingId != null && assignments.stream().anyMatch(assignment ->
                assignment.getRole().getCode().equals("SECRETARY")
                        && officeBuildingId.equals(assignment.getScopeOfficeBuildingId()));
    }
}
