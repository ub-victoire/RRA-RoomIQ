package rw.rra.roomiq.organization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import rw.rra.roomiq.common.web.DomainException;
import rw.rra.roomiq.organization.domain.dto.CreateCountryRequest;
import rw.rra.roomiq.organization.domain.dto.CreateDepartmentRequest;
import rw.rra.roomiq.organization.domain.dto.CreateDistrictRequest;
import rw.rra.roomiq.organization.domain.dto.CreateOfficeBuildingRequest;
import rw.rra.roomiq.organization.domain.dto.CreateProvinceRequest;
import rw.rra.roomiq.organization.domain.entity.DepartmentStatus;
import rw.rra.roomiq.organization.domain.service.DepartmentHierarchyService;
import rw.rra.roomiq.organization.domain.service.OrganizationManagementService;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OrganizationPostgresMigrationTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DepartmentHierarchyService departmentHierarchyService;

    @Autowired
    private OrganizationManagementService organizationManagementService;

    @Test
    void postgresRunsAllMigrationsWithHibernateSchemaValidation() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4");
    }

    @Test
    void postgresEnforcesHierarchyForeignKeysAndApprovedUniqueness() {
        UUID countryId = UUID.randomUUID();
        UUID provinceId = UUID.randomUUID();
        String countryName = "PostgreSQL Country " + countryId;
        jdbcTemplate.update("INSERT INTO country (id, name) VALUES (?, ?)", countryId, countryName);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO province (id, country_id, name) VALUES (?, ?, ?)",
                UUID.randomUUID(), UUID.randomUUID(), "Missing country"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO country (id, name) VALUES (?, ?)", UUID.randomUUID(), countryName))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("INSERT INTO province (id, country_id, name) VALUES (?, ?, ?)",
                provinceId, countryId, "PostgreSQL Province " + provinceId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO province (id, country_id, name) VALUES (?, ?, ?)",
                UUID.randomUUID(), countryId, "PostgreSQL Province " + provinceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void postgresEnforcesNonblankAndDepartmentSelfParentChecks() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO country (id, name) VALUES (?, ?)", UUID.randomUUID(), "   "))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID departmentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO department (id, parent_department_id, name, code, status)
                VALUES (?, ?, ?, ?, ?)
                """, departmentId, departmentId, "Self parent", "SELF-" + departmentId, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentOppositeDepartmentReparentsCannotCreateACycle() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UUID countryId = organizationManagementService.createCountry(
                new CreateCountryRequest("Concurrent Country " + suffix, null, null)).id();
        UUID provinceId = organizationManagementService.createProvince(
                new CreateProvinceRequest(countryId, "Concurrent Province " + suffix, null)).id();
        UUID districtId = organizationManagementService.createDistrict(
                new CreateDistrictRequest(provinceId, "Concurrent District " + suffix, null)).id();
        UUID buildingId = organizationManagementService.createOfficeBuilding(
                new CreateOfficeBuildingRequest(districtId, "Concurrent Building " + suffix,
                        "CONCURRENT-" + suffix, null, null, null, null)).id();
        UUID firstId = organizationManagementService.createDepartment(
                new CreateDepartmentRequest(buildingId, null, "First " + suffix, "FIRST-" + suffix,
                        DepartmentStatus.ACTIVE)).id();
        UUID secondId = organizationManagementService.createDepartment(
                new CreateDepartmentRequest(buildingId, null, "Second " + suffix, "SECOND-" + suffix,
                        DepartmentStatus.ACTIVE)).id();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstReparent = executor.submit(() -> {
                awaitStart(start);
                departmentHierarchyService.reparent(firstId, secondId);
            });
            Future<?> secondReparent = executor.submit(() -> {
                awaitStart(start);
                departmentHierarchyService.reparent(secondId, firstId);
            });
            start.countDown();

            Throwable firstFailure = failureOf(firstReparent);
            Throwable secondFailure = failureOf(secondReparent);
            int successfulUpdates = (firstFailure == null ? 1 : 0) + (secondFailure == null ? 1 : 0);

            assertThat(successfulUpdates).isEqualTo(1);
            Throwable rejectedUpdate = firstFailure == null ? secondFailure : firstFailure;
            assertThat(isExpectedConcurrencyRejection(rejectedUpdate))
                    .as("Unexpected concurrent Department update rejection: %s", rejectedUpdate)
                    .isTrue();
            UUID firstParentId = organizationManagementService.getDepartment(firstId).parentDepartmentId();
            UUID secondParentId = organizationManagementService.getDepartment(secondId).parentDepartmentId();
            assertThat(firstParentId == null || secondParentId == null).isTrue();
            assertThat(firstId.equals(secondParentId) && secondId.equals(firstParentId)).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    private static Throwable failureOf(Future<?> operation) throws Exception {
        try {
            operation.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private static boolean isExpectedConcurrencyRejection(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DomainException domainException
                    && "INVALID_DEPARTMENT_PARENT".equals(domainException.code())) {
                return true;
            }
            if (cause instanceof SQLException sqlException && "40001".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to start concurrent Department updates");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before concurrent Department update", exception);
        }
    }
}
