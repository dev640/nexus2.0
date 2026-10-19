package com.nexus.backend.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the provisioning endpoint and tenant-scoped routes over real HTTP
 * with two separately provisioned companies on throwaway PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "nexus.auth.rate-limit.max-requests=1000"
)
class TwoCompanyIsolationTest {

    private static final String SUPER_ADMIN_EMAIL = "provisioning-root@company-phase.test";
    private static final String SUPER_ADMIN_PASSWORD = "Root-Only-Test-Password-42!";
    private static final String NEW_ADMIN_PASSWORD = "New-Admin-Test-Password-42!";

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.secret", () -> "two-company-isolation-test-secret-0123456789");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${local.server.port}")
    private int port;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void provisionedCompaniesRemainIsolatedAcrossRealHttpRequests() throws Exception {
        ensureSuperAdmin();
        String superAdminToken = login(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASSWORD).path("token").asText();
        int companyCountBefore = json(get("/api/admin/companies", superAdminToken).body()).size();

        JsonNode alpha = createCompany(superAdminToken, "Alpha Works", "Alpha Admin",
            "alpha-admin@company-phase.test");
        JsonNode beta = createCompany(superAdminToken, "Beta Industries", "Beta Admin",
            "beta-admin@company-phase.test");

        assertThat(alpha.path("generatedPassword").asText()).isNotBlank();
        assertThat(beta.path("generatedPassword").asText()).isNotBlank();
        assertThat(alpha.path("role").asText()).isEqualTo("ADMIN");
        assertThat(beta.path("role").asText()).isEqualTo("ADMIN");

        AdminSession alphaAdmin = completeFirstSignIn(alpha);
        AdminSession betaAdmin = completeFirstSignIn(beta);

        long alphaProjectId = createProject(alphaAdmin.token(), "Alpha Project");
        long betaProjectId = createProject(betaAdmin.token(), "Beta Project");

        JsonNode alphaProjects = json(get("/api/projects", alphaAdmin.token()).body());
        JsonNode betaProjects = json(get("/api/projects", betaAdmin.token()).body());
        assertThat(projectNames(alphaProjects)).containsExactly("Alpha Project");
        assertThat(projectNames(betaProjects)).containsExactly("Beta Project");

        assertThat(get("/api/projects/" + alphaProjectId, betaAdmin.token()).statusCode())
            .isEqualTo(404);
        assertThat(post("/api/chat/channels/dm/" + beta.path("adminId").asLong(), alphaAdmin.token(), null)
            .statusCode()).isEqualTo(404);

        JsonNode companies = json(get("/api/admin/companies", superAdminToken).body());
        assertThat(companies.size()).isEqualTo(companyCountBefore + 2);
        long alphaCompanyId = alpha.path("companyId").asLong();
        long betaCompanyId = beta.path("companyId").asLong();
        assertThat(companyById(companies, alphaCompanyId).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(companyById(companies, alphaCompanyId).path("employeeCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(companyById(companies, betaCompanyId).path("employeeCount").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode alphaAccounts = json(get(
            "/api/admin/accounts?companyId=" + alphaCompanyId, superAdminToken).body());
        assertThat(alphaAccounts.size()).isEqualTo(1);
        assertThat(alphaAccounts.get(0).path("companyId").asLong()).isEqualTo(alphaCompanyId);
        assertThat(alphaAccounts.get(0).path("role").asText()).isEqualTo("ADMIN");

        JsonNode betaAccounts = json(get(
            "/api/admin/accounts?companyId=" + betaCompanyId, superAdminToken).body());
        assertThat(betaAccounts.size()).isEqualTo(1);
        assertThat(betaAccounts.get(0).path("companyId").asLong()).isEqualTo(betaCompanyId);
        assertThat(betaAccounts.get(0).path("role").asText()).isEqualTo("ADMIN");

        assertThat(post("/api/admin/companies", alphaAdmin.token(), Map.of(
            "name", "Forbidden Company",
            "adminName", "Forbidden Admin",
            "adminEmail", "forbidden@company-phase.test"))
            .statusCode()).isEqualTo(403);

        HttpResponse<String> duplicate = post("/api/admin/companies", superAdminToken, Map.of(
            "name", "aLPHa wORKS",
            "adminName", "Another Admin",
            "adminEmail", "another-admin@company-phase.test"));
        assertThat(duplicate.statusCode()).isEqualTo(400);

        HttpResponse<String> duplicateAdminEmail = post("/api/admin/companies", superAdminToken, Map.of(
            "name", "Gamma Confidential",
            "adminName", "Another Admin",
            "adminEmail", "alpha-admin@company-phase.test"));
        assertThat(duplicateAdminEmail.statusCode()).isEqualTo(409);
        assertThat(duplicateAdminEmail.body()).doesNotContain("alpha-admin@company-phase.test")
            .doesNotContain("Alpha Works");
        assertThat(json(get("/api/admin/companies", superAdminToken).body()).size())
            .isEqualTo(companyCountBefore + 2);

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT actor_email, company_id, metadata::text AS metadata "
                + "FROM audit_log WHERE action = 'COMPANY_CREATED' AND entity_id = ?",
            Long.toString(alphaCompanyId));
        assertThat(audit.get("actor_email")).isEqualTo(SUPER_ADMIN_EMAIL);
        assertThat(audit.get("company_id")).isNull();
        assertThat(audit.get("metadata").toString()).contains("alpha-admin@company-phase.test")
            .doesNotContain(alpha.path("generatedPassword").asText());
    }

    private void ensureSuperAdmin() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE lower(email) = lower(?)",
            Integer.class,
            SUPER_ADMIN_EMAIL);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update(
            "INSERT INTO users (name, email, password, role, company_id, employee_code, must_change_password) "
                + "VALUES (?, ?, ?, 'SUPER_ADMIN', NULL, NULL, FALSE)",
            "Provisioning Root",
            SUPER_ADMIN_EMAIL,
            passwordEncoder.encode(SUPER_ADMIN_PASSWORD));
    }

    private JsonNode createCompany(String token, String company, String adminName, String adminEmail)
        throws Exception {
        HttpResponse<String> response = post("/api/admin/companies", token, Map.of(
            "name", company,
            "adminName", adminName,
            "adminEmail", adminEmail));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location").orElse(null)).isEqualTo("/api/admin/companies");
        assertThat(response.headers().firstValue("Cache-Control").orElse(null)).isEqualTo("no-store");
        JsonNode body = json(response.body());
        assertThat(body.path("generatedPassword").asText()).isNotBlank();
        return body;
    }

    private AdminSession completeFirstSignIn(JsonNode created) throws Exception {
        String email = created.path("adminEmail").asText();
        String generatedPassword = created.path("generatedPassword").asText();
        JsonNode firstLogin = login(email, generatedPassword);
        assertThat(firstLogin.path("user").path("mustChangePassword").asBoolean()).isTrue();
        String temporaryToken = firstLogin.path("token").asText();

        HttpResponse<String> changed = post("/api/users/me/password", temporaryToken, Map.of(
            "currentPassword", generatedPassword,
            "newPassword", NEW_ADMIN_PASSWORD));
        assertThat(changed.statusCode()).isEqualTo(200);
        JsonNode renewed = json(changed.body());
        assertThat(renewed.path("user").path("mustChangePassword").asBoolean()).isFalse();
        assertThat(get("/api/users/me", temporaryToken).statusCode()).isEqualTo(401);
        assertThat(get("/api/users/me", renewed.path("token").asText()).statusCode()).isEqualTo(200);
        return new AdminSession(renewed.path("token").asText(), created.path("adminId").asLong());
    }

    private long createProject(String token, String name) throws Exception {
        HttpResponse<String> response = post("/api/projects", token, Map.of("name", name));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response.body()).path("id").asLong();
    }

    private JsonNode login(String email, String password) throws Exception {
        HttpResponse<String> response = post("/api/auth/login", null, Map.of(
            "email", email,
            "password", password));
        assertThat(response.statusCode()).isEqualTo(200);
        return json(response.body());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
            .header("Accept", "application/json")
            .GET();
        addToken(request, token);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json");
        addToken(request, token);
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        return HTTP.send(request.POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static void addToken(HttpRequest.Builder request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private static java.util.List<String> projectNames(JsonNode projects) {
        java.util.List<String> names = new java.util.ArrayList<>();
        projects.forEach(project -> names.add(project.path("name").asText()));
        return names;
    }

    private static JsonNode companyById(JsonNode companies, long companyId) {
        for (JsonNode company : companies) {
            if (company.path("id").asLong() == companyId) {
                return company;
            }
        }
        throw new AssertionError("Company id was not present in the SUPER_ADMIN listing");
    }

    private record AdminSession(String token, long adminId) {}
}
