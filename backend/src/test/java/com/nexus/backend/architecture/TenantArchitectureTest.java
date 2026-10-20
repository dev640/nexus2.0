package com.nexus.backend.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.backend.service.CompanySuspensionService;
import com.nexus.backend.service.SuperAdminService;
import com.nexus.backend.tenant.TenantContext;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The architecture rules docs/MULTI_TENANCY.md promised as "phase 4".
 *
 * <p>Four of the rollout's invariants are conventions today: they hold because
 * every author so far knew them, not because anything fails when they stop
 * holding. {@code EntitySchemaConformanceTest} already proves the mapped-entity
 * boundary — which entities are tenant-aware and which are the documented
 * exceptions — with reflection, so these tests do not repeat it. What is left is
 * the four a reviewer has to notice by hand:
 *
 * <ol>
 *   <li>a write endpoint with no authorization decision at all,</li>
 *   <li>a listing that walks every company's rows,</li>
 *   <li>a tenant controller reaching into the cross-company operator service,</li>
 *   <li>anything but the authentication filter setting or clearing the tenant.</li>
 * </ol>
 *
 * <p>The first is the risk named in {@code backend/SECURITY_AUDIT.md}: the
 * endpoints that are annotated are fine, and the danger is the next one added
 * without an annotation. Reads are deliberately not included — most of them are
 * legitimately open to any authenticated member of the company and are scoped in
 * the service layer, so requiring a role annotation on them would encode a rule
 * nobody wants and be silenced with a blanket allowlist. Writes are where an
 * omission costs data.
 *
 * <p>Classes are imported from {@code target/classes} rather than by package
 * name, because a package-name import also resolves {@code target/test-classes}:
 * test doubles implementing repository interfaces would then be scanned as
 * production code and the rules would fail on the tests themselves.
 */
class TenantArchitectureTest {

    private static final JavaClasses CLASSES = importProductionClasses();

    /**
     * Writes that any authenticated account may perform on its own state, and
     * which therefore carry no role decision. This mirrors the carve-out in
     * {@code WorkspaceWrite}'s javadoc: a VIEWER is read-only in workspace
     * content, not in their own profile, inbox or device subscriptions.
     *
     * <p>Every entry is a deliberate decision, which is the point of the list:
     * a new write endpoint fails the test until somebody writes the reason here
     * or annotates the endpoint.
     */
    private static final Set<String> PERSONAL_STATE_WRITES = Set.of(
        // Sign-in itself. These run before a session exists, so there is no
        // role to test; the password is the credential.
        "AuthController#login",
        "AuthController#refresh",
        "AuthController#requestPasswordReset",
        // The caller's own account: their name, their password, their avatar.
        "UserController#updateMe",
        "UserController#changeOwnPassword",
        "UserController#uploadAvatar",
        "UserController#deleteAvatar",
        // Reading a channel and acknowledging messages are presence, not
        // content: a read-only member still marks their own inbox read.
        "ChatController#joinChannel",
        "ChatController#leaveChannel",
        "ChatController#markRead",
        "MessageController#markRead",
        "NotificationController#markRead",
        "NotificationController#archive",
        "NotificationController#markAllRead",
        // This account's own notification rows, re-stamped after a company
        // change. The service touches only rows addressed to the caller.
        "NotificationController#reStampCompany",
        // One browser's push endpoint. Scoped to the caller's own subscription,
        // and the self-check notifies only the caller's own devices
        // (PushSubscriptionService.sendTest uses the caller's user id).
        "PushController#subscribe",
        "PushController#unsubscribe",
        "PushController#test",
        // Asking the copilot, and the per-user AI conversation controls. The
        // quota is enforced per account by AiRateLimitFilter, not by role, and
        // the only rows written belong to the caller's own conversation.
        "AiController#ask",
        "AiController#regenerate",
        "AiController#deleteConversation",
        "CopilotController#ask",
        // Mail to one named person, writing to the whole company, and deleting
        // mail you sent or received. Internal mail is correspondence rather than
        // workspace content, so a read-only account keeps it — a deliberate
        // policy that ViewerReadOnlySecurityTest.personalEndpointsStayUsableForViewer
        // pins for both /messages and /messages/broadcast. Ownership is the
        // check, not role: MessageService refuses unless the caller is sender or
        // recipient.
        "MessageController#send",
        "MessageController#broadcast",
        "MessageController#delete"
    );

    @Test
    void everyWriteEndpointDeclaresWhoMayCallIt() {
        assertThat(CLASSES).as("production classes must be on the classpath to analyse")
            .isNotEmpty();

        List<String> violations = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();

        for (JavaClass owner : CLASSES) {
            if (!owner.getPackageName().startsWith("com.nexus.backend.web")) {
                continue;
            }
            for (JavaMethod method : owner.getMethods()) {
                if (!isWriteMapping(method)) {
                    continue;
                }
                if (isBareRequestMapping(method)) {
                    // @RequestMapping without a shortcut annotation cannot be
                    // classified here, and silently skipping it would let a new
                    // write endpoint opt out of this rule by spelling it longhand.
                    unclassified.add(key(owner, method));
                    continue;
                }
                if (isAuthorized(method) || PERSONAL_STATE_WRITES.contains(key(owner, method))) {
                    continue;
                }
                violations.add(key(owner, method));
            }
        }

        assertThat(unclassified)
            .as("write endpoints must use @PostMapping/@PutMapping/@PatchMapping/@DeleteMapping "
                + "so this test can classify them; a bare @RequestMapping hides them from it")
            .isEmpty();

        assertThat(violations)
            .as("these write endpoints carry no @PreAuthorize (directly, on the class, or through "
                + "@WorkspaceWrite/@WorkspaceManage). Annotate them, or add them to "
                + "PERSONAL_STATE_WRITES with the reason they are safe for every authenticated "
                + "account — a VIEWER must not be able to change workspace content")
            .isEmpty();
    }

    @Test
    void onlyTheOperatorServiceListsEveryCompany() {
        List<String> violations = new ArrayList<>();

        for (JavaClass owner : CLASSES) {
            for (JavaMethodCall call : owner.getMethodCallsFromSelf()) {
                if (!"findAll".equals(call.getTarget().getName())) {
                    continue;
                }
                if (!call.getTarget().getOwner().isAssignableTo(Repository.class)) {
                    continue;
                }
                if ("SuperAdminService".equals(owner.getSimpleName())) {
                    continue;
                }
                violations.add(owner.getSimpleName() + " calls " + call.getTarget().getFullName());
            }
        }

        assertThat(violations)
            .as("a repository findAll() walks every company's rows. Only SuperAdminService may do "
                + "that, because it is reachable solely from a hasRole('SUPER_ADMIN') endpoint; "
                + "every other read must be company-predicated (findByCompanyId / "
                + "findByIdAndCompanyId) or ownership-checked")
            .isEmpty();
    }

    @Test
    void onlyTheOperatorControllerReachesTheCrossCompanyServices() {
        noClasses()
            .that().resideInAPackage("com.nexus.backend.web..")
            .and().doNotHaveSimpleName("SuperAdminController")
            .should().dependOnClassesThat().areAssignableTo(SuperAdminService.class)
            .because("a tenant controller must not expose cross-company listings; the operator "
                + "console has its own endpoint for that")
            .check(CLASSES);

        noClasses()
            .that().resideInAPackage("com.nexus.backend.web..")
            .and().doNotHaveSimpleName("SuperAdminController")
            .should().dependOnClassesThat().areAssignableTo(CompanySuspensionService.class)
            .because("locking a company out is an operator action, not a workspace one")
            .check(CLASSES);
    }

    @Test
    void onlyTheAuthenticationFilterEstablishesTheTenant() {
        noClasses()
            .that().resideOutsideOfPackages(
                "com.nexus.backend.security..", "com.nexus.backend.tenant..")
            .should().callMethod(TenantContext.class, "setTenantId", Long.class)
            .because("the tenant is established once, from the account row, in "
                + "JwtAuthenticationFilter; a service that sets it would be choosing its own "
                + "company instead of reading the caller's")
            .check(CLASSES);

        noClasses()
            .that().resideOutsideOfPackages(
                "com.nexus.backend.security..", "com.nexus.backend.tenant..")
            .should().callMethod(TenantContext.class, "clear")
            .because("only the filter that set the tenant may clear it, in its finally block")
            .check(CLASSES);
    }

    private static JavaClasses importProductionClasses() {
        Path classes = Path.of("target", "classes");
        if (!Files.isDirectory(classes)) {
            throw new IllegalStateException(
                "target/classes is missing; this test reads compiled production code. "
                    + "Build first (./mvnw -B test) rather than running it from a bare IDE "
                    + "workspace with no output directory.");
        }
        return new ClassFileImporter().importPath(classes);
    }

    /** A method mapped to POST/PUT/PATCH/DELETE through a Spring shortcut annotation. */
    private static boolean isWriteMapping(JavaMethod method) {
        return method.isAnnotatedWith(PostMapping.class)
            || method.isAnnotatedWith(PutMapping.class)
            || method.isAnnotatedWith(PatchMapping.class)
            || method.isAnnotatedWith(DeleteMapping.class);
    }

    /** A mapping declared longhand, which {@link #isWriteMapping} cannot classify. */
    private static boolean isBareRequestMapping(JavaMethod method) {
        return method.isAnnotatedWith(RequestMapping.class);
    }

    /**
     * Covered by an authorization decision: on the method, on its controller, or
     * through a composed annotation such as {@code @WorkspaceWrite}, whose own
     * {@code @PreAuthorize} is what makes it meta-annotated with it.
     */
    private static boolean isAuthorized(JavaMethod method) {
        JavaClass owner = method.getOwner();
        return method.isAnnotatedWith(PreAuthorize.class)
            || method.isMetaAnnotatedWith(PreAuthorize.class)
            || owner.isAnnotatedWith(PreAuthorize.class)
            || owner.isMetaAnnotatedWith(PreAuthorize.class);
    }

    private static String key(JavaClass owner, JavaMethod method) {
        return owner.getSimpleName() + "#" + method.getName();
    }
}
