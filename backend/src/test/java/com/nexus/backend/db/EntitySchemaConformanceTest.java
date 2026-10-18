package com.nexus.backend.db;

import com.nexus.backend.domain.company.Company;
import com.nexus.backend.domain.workspace.Workspace;
import com.nexus.backend.tenant.TenantAware;
import com.nexus.backend.tenant.TenantContext;
import com.nexus.backend.tenant.TenantScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real application against a database built by the real migrations.
 *
 * <p>This is the test the phase-1 notes said could not exist yet: the entities
 * did not know about {@code company_id}, so starting the context over the V18+
 * schema would have failed for reasons unrelated to the migrations. Now that
 * they do, {@code spring.jpa.hibernate.ddl-auto=validate} makes the context
 * startup itself the assertion — every mapped column must exist, with a
 * compatible type — and the checks below go further, pinning the parts of the
 * tenancy wiring that a validator cannot see.
 */
@SpringBootTest
class EntitySchemaConformanceTest {

    /**
     * One container for the class, and deliberately never stopped: it is a
     * throwaway database whose lifetime is the test JVM's, and the Ryuk reaper
     * removes it either way. Shutting it down per class would mean paying for a
     * fresh migration run on every future test that shares this context.
     */
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse(DbTestSupport.IMAGE));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void pointAtTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // JwtUtil refuses to start without a secret of at least 32 bytes, by design.
        registry.add("jwt.secret", () -> "entity-schema-conformance-test-secret-0123456789");
    }

    /**
     * Entities that deliberately do not implement {@link TenantAware}, and why.
     *
     * <p>Keep this list equal to the exceptions in docs/MULTI_TENANCY.md. It is
     * the enforcement half of that document: a new entity either carries a
     * company or has to be justified here in writing.
     */
    private static final Set<String> NON_TENANT_ENTITIES = Set.of(
        // It *is* the tenant. Every other table's company_id points at this table's
        // id, so a companies.company_id would be a company owning itself.
        "Company",
        // A global action (SUPER_ADMIN) belongs to no company, so an audit row
        // would be refused by a tenant stamp exactly when it matters most: the
        // action has to be recorded even when there is no company to file it under.
        "AuditLog"
    );

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private javax.sql.DataSource dataSource;

    /**
     * A real Hibernate session, because the stamp listener is a JPA lifecycle
     * callback: it only runs when the provider persists something. Nothing else in
     * this suite exercises a write, so without these tests the mechanism every
     * insert depends on would be unverified.
     */
    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * The context started, so Hibernate's schema validation passed: every entity
     * in the model matches the migrated database. If this test fails at startup,
     * the message names the missing or mistyped column.
     */
    @Test
    void theContextStartsAndHibernateValidatesTheModelAgainstTheSchema() {
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isNotEmpty();
    }

    /**
     * Every mapped entity is either company-owned or a documented exception.
     *
     * <p>This is what stops the next table being added without a decision about
     * which company its rows belong to — the failure mode being a table that looks
     * scoped but is not.
     */
    @Test
    void everyEntityIsTenantAwareOrADocumentedException() {
        Set<String> offenders = new TreeSet<>();
        int checked = 0;

        for (EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            if (NON_TENANT_ENTITIES.contains(type.getName())) {
                continue;
            }
            checked++;
            if (!TenantAware.class.isAssignableFrom(type.getJavaType())) {
                offenders.add(type.getJavaType().getName());
            }
        }

        assertThat(checked)
            .as("entities checked; a model of zero entities would make this test vacuous")
            .isGreaterThan(10);
        assertThat(offenders)
            .as("entities that carry tenant data but do not implement TenantAware — "
                + "either implement it or add the entity to NON_TENANT_ENTITIES and to "
                + "docs/MULTI_TENANCY.md, with the reason")
            .isEmpty();
    }

    /**
     * The documented exceptions are exactly the ones the code claims: if an entity
     * quietly stops being an exception, the list above must be corrected rather
     * than left to rot.
     */
    @Test
    void theExceptionListMatchesTheModel() {
        Set<String> names = new TreeSet<>();
        for (EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            names.add(type.getName());
        }

        assertThat(names).containsAll(NON_TENANT_ENTITIES);
        assertThat(NON_TENANT_ENTITIES)
            .as("every listed exception must actually be a mapped entity, and the list "
                + "must not grow silently — each entry needs a written reason here and in "
                + "docs/MULTI_TENANCY.md")
            .hasSize(2);
    }

    /**
     * Every {@link TenantAware} entity's table really has a non-nullable
     * {@code company_id} — the mapping and the schema agree on both the column and
     * the NOT NULL, which is the pair the whole scoping model rests on.
     *
     * <p>{@code users} is the one table whose column is nullable, because a
     * SUPER_ADMIN belongs to no company. {@code companies} and {@code audit_log}
     * are absent from the check because they are not tenant-owned: one is the
     * tenant, the other is a global record that must survive without one.
     */
    @Test
    void everyTenantAwareEntitysTableHasACompanyIdColumn() throws Exception {
        Set<String> tables = new LinkedHashSet<>();
        for (EntityType<?> type : entityManagerFactory.getMetamodel().getEntities()) {
            if (NON_TENANT_ENTITIES.contains(type.getName())) {
                continue;
            }
            jakarta.persistence.Table table = type.getJavaType().getAnnotation(jakarta.persistence.Table.class);
            assertThat(table).as("%s must declare its table", type.getName()).isNotNull();
            tables.add(table.name());
        }

        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                Boolean nullable = columnIsNullable(connection, table, "company_id");
                assertThat(nullable)
                    .as("column company_id on %s", table)
                    .isNotNull();

                if (table.equals("users")) {
                    assertThat(nullable)
                        .as("users.company_id stays nullable: a SUPER_ADMIN has no company")
                        .isTrue();
                } else {
                    assertThat(nullable)
                        .as("company_id on %s must be NOT NULL", table)
                        .isFalse();
                }
            }
        }
    }

    // ------------------------------------------------- the stamp, actually running

    /** The one company V18 creates; every backfilled row belongs to it. */
    private Long defaultCompanyId() {
        return entityManager.createQuery("select c.id from Company c", Long.class)
            .getResultList().get(0);
    }

    /**
     * A row saved while a tenant is in scope is stamped with that company, without
     * the caller ever mentioning it — which is the whole point of the listener.
     */
    @Test
    @Transactional
    void aNewRowIsStampedWithTheCompanyInScope() {
        Long companyId = defaultCompanyId();

        Workspace workspace = TenantScope.call(companyId, () -> {
            Workspace created = new Workspace("Stamped workspace", null);
            entityManager.persist(created);
            return created;
        });

        assertThat(workspace.getCompanyId()).isEqualTo(companyId);
    }

    /**
     * With no tenant and nothing assigned, the insert is refused rather than
     * written with a null company — the failure has to name the entity, because a
     * NOT NULL violation does not.
     */
    @Test
    @Transactional
    void aNewRowWithNoCompanyIsRefused() {
        TenantContext.clear();
        Workspace orphan = new Workspace("Nobody's workspace", null);

        assertThatThrownBy(() -> entityManager.persist(orphan))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no company")
            .hasMessageContaining("Workspace");
    }

    /**
     * An explicitly assigned company beats the ambient tenant. Importing or
     * migrating another company's rows is a deliberate act, and silently
     * re-stamping it as the caller's would corrupt the data rather than fail.
     */
    @Test
    @Transactional
    void anExplicitCompanyWinsOverTheTenantInScope() {
        Long stampCompany = defaultCompanyId();
        Long explicitCompany = createSecondCompany();

        Workspace workspace = TenantScope.call(stampCompany, () -> {
            Workspace created = new Workspace("Imported workspace", null);
            created.setCompanyId(explicitCompany);
            entityManager.persist(created);
            return created;
        });

        assertThat(workspace.getCompanyId()).isEqualTo(explicitCompany);
    }

    /**
     * A second company, created so the tests above can tell the two apart. Rolled
     * back with the test transaction.
     */
    private Long createSecondCompany() {
        return TenantScope.call(null, () -> {
            Company company = new Company("Second Co");
            entityManager.persist(company);
            entityManager.flush();
            return company.getId();
        });
    }

    /**
     * True when the column is nullable, false when it is NOT NULL, null when the
     * table has no such column at all. Distinguishing "absent" from "nullable"
     * matters: an entity whose company_id mapping was forgotten produces neither
     * Hibernate complaint nor a NOT NULL violation until something inserts.
     */
    private static Boolean columnIsNullable(Connection connection, String table, String column)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT is_nullable FROM information_schema.columns "
                     + "WHERE table_schema = 'public' AND table_name = '" + table + "'"
                     + " AND column_name = '" + column + "'")) {
            if (!rs.next()) {
                return null;
            }
            return "YES".equalsIgnoreCase(rs.getString(1));
        }
    }
}
