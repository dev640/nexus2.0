package com.nexus.backend.service.push;

import com.nexus.backend.domain.push.PushSubscription;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.PushSelfCheckResponse;
import com.nexus.backend.dto.PushSubscriptionRequest;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.PushSubscriptionRepository;
import com.nexus.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registering and silencing the browsers an account can be notified on.
 *
 * <p>The interesting part is what is refused. A push endpoint is a URL supplied
 * by a client and then dialled by the server, so without a check this feature is
 * a request forwarder: an endpoint aimed at cloud metadata or a neighbouring
 * service would make the application fetch it on the caller's behalf. Real push
 * services answer on https at a public hostname, so the tests below pin both
 * halves of that rule.
 */
@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock private PushSubscriptionRepository subscriptionRepository;
    @Mock private UserRepository userRepository;
    @Mock private WebPushSender sender;

    private PushSubscriptionService service;

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123_def-456";
    private static final String P256DH = "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM";
    private static final String AUTH = "tBHItJI5svbpez7KI4CCXg";

    @BeforeEach
    void setUp() {
        service = new PushSubscriptionService(subscriptionRepository, userRepository, sender);
        authenticateAs("vidhi@nexus.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsThisBrowserAgainstTheSignedInAccount() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        service.subscribe(new PushSubscriptionRequest(ENDPOINT, new PushSubscriptionRequest.Keys(P256DH, AUTH)));

        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(saved.getValue().getP256dh()).isEqualTo(P256DH);
        assertThat(saved.getValue().getAuth()).isEqualTo(AUTH);
        assertThat(saved.getValue().getUser().getId()).isEqualTo(3L);
    }

    @Test
    void reRegisteringRefreshesTheRowInsteadOfAddingASecondOne() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        PushSubscription existing = new PushSubscription();
        existing.setId(9L);
        existing.setUser(user(3L));
        existing.setEndpoint(ENDPOINT);
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        service.subscribe(new PushSubscriptionRequest(ENDPOINT, new PushSubscriptionRequest.Keys(P256DH, AUTH)));

        // The browser re-files its subscription on every load, so this is the
        // common path, not an error path. The same row is updated — never a second
        // row for a device that would then be pushed to twice.
        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(subscriptionRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(9L);
        assertThat(saved.getValue().getAuth()).isEqualTo(AUTH);
    }

    /**
     * A browser that had a different account signed in keeps its endpoint, and the
     * endpoint moves to the account that is now using it. Otherwise signing out and
     * signing in as somebody else would silently misroute their notifications.
     */
    @Test
    void movesAKnownEndpointToWhoeverPresentsItNow() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        PushSubscription someoneElses = new PushSubscription();
        someoneElses.setId(9L);
        someoneElses.setUser(user(8L));
        someoneElses.setEndpoint(ENDPOINT);
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(someoneElses));

        service.subscribe(new PushSubscriptionRequest(ENDPOINT, new PushSubscriptionRequest.Keys(P256DH, AUTH)));

        assertThat(someoneElses.getUser().getId()).isEqualTo(3L);
    }

    @Test
    void refusesAnEndpointThatIsNotHttps() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));

        for (String hostile : List.of(
            "http://fcm.googleapis.com/fcm/send/abc",
            "/etc/passwd",
            "javascript:alert(1)",
            "not a url at all",
            "file:///etc/passwd")) {
            assertThatThrownBy(() -> service.subscribe(
                new PushSubscriptionRequest(hostile, new PushSubscriptionRequest.Keys(P256DH, AUTH))))
                .as(hostile)
                .isInstanceOf(ValidationException.class);
        }

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void refusesAnEndpointAimedInsideTheNetwork() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));

        for (String internal : List.of(
            "https://127.0.0.1/push",
            "https://localhost/push",
            "https://10.0.0.5/push",
            "https://192.168.1.10/push",
            "https://172.16.4.4/push",
            // The cloud metadata address, the classic reason this check exists.
            "https://169.254.169.254/latest/meta-data/",
            "https://[::1]/push",
            "https://redis.internal/push",
            "https://printer.local/push")) {
            assertThatThrownBy(() -> service.subscribe(
                new PushSubscriptionRequest(internal, new PushSubscriptionRequest.Keys(P256DH, AUTH))))
                .as(internal)
                .isInstanceOf(ValidationException.class);
        }

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void silencesTheCallersOwnDevice() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        PushSubscription mine = new PushSubscription();
        mine.setId(9L);
        mine.setUser(user(3L));
        mine.setEndpoint(ENDPOINT);
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(mine));

        service.unsubscribe(ENDPOINT);

        verify(subscriptionRepository).delete(mine);
    }

    @Test
    void cannotSilenceSomebodyElsesDevice() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        PushSubscription theirs = new PushSubscription();
        theirs.setId(9L);
        theirs.setUser(user(8L));
        theirs.setEndpoint(ENDPOINT);
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(theirs));

        service.unsubscribe(ENDPOINT);

        verify(subscriptionRepository, never()).delete(any(PushSubscription.class));
    }

    @Test
    void removingSomethingThatIsNotThereIsNotAnError() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(subscriptionRepository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        // The caller asked for the outcome and already has it; failing here would
        // only make "turn off notifications" report a problem it did not have.
        service.unsubscribe(ENDPOINT);
        service.unsubscribe(null);
        service.unsubscribe("   ");

        verify(subscriptionRepository, never()).delete(any(PushSubscription.class));
    }

    @Test
    void sendsATestToTheCallersOwnDevices() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(sender.sendToUser(eq(3L), any())).thenReturn(2);

        assertThat(service.sendTest()).isEqualTo(2);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(sender).sendToUser(eq(3L), payload.capture());
        assertThat(payload.getValue())
            .containsEntry("title", "Nexus")
            // Opens the workspace rather than a dead path when tapped.
            .containsEntry("url", "/");
    }

    @Test
    void passesTheServersPublicKeyThroughToBrowsers() {
        when(sender.publicKey()).thenReturn("BHcy7Ysvq4B8mXp");

        assertThat(service.publicKey()).isEqualTo("BHcy7Ysvq4B8mXp");
    }

    @Test
    void countsOnlyTheCallersDevices() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(subscriptionRepository.countByUserId(anyLong())).thenReturn(2L);

        assertThat(service.deviceCount()).isEqualTo(2L);
        verify(subscriptionRepository).countByUserId(3L);
    }

    /**
     * Sending is a write, and this pins that in the one place a unit test can see
     * it.
     *
     * <p>The failure this guards against is invisible to every other test here:
     * with {@code readOnly = true} the pruning delete in the sender is silently
     * discarded, so the mocked-repository test above still passes while a dead
     * endpoint is retried forever in production. That is exactly how it reached an
     * end-to-end run before it was found, and an annotation is the only handle a
     * test without a database has on it.
     *
     * <p>What this does not prove: that the delete actually commits. Only a real
     * transaction can show that, which is why the deployed walkthrough sends a
     * test notification to an endpoint the push service has disowned and then
     * counts the rows.
     */
    @Test
    void sendingIsNotReadOnlyBecauseItPrunesDeadSubscriptions() throws Exception {
        Transactional transactional = PushSubscriptionService.class
            .getMethod("sendTest")
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isFalse();
    }

    /**
     * The self-check is the answer to "why is this phone not buzzing?", so it has to
     * carry the whole answer: the key the server signs with, whether a device is even
     * registered, and what the push service last said.
     */
    @Test
    void reportsTheKeyTheDevicesAndTheLastAttempt() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(sender.isEnabled()).thenReturn(true);
        when(sender.publicKeyFingerprint()).thenReturn("0123456789ab");
        when(sender.publicKeyMatchesPrivateKey()).thenReturn(Boolean.TRUE);
        when(sender.subject()).thenReturn("mailto:ops@nexus.test");
        when(sender.ttlHours()).thenReturn(24L);
        when(sender.lastAttempt(3L)).thenReturn(new PushAttempt(
            Instant.parse("2026-10-14T09:30:00Z"), 2, 1, PushAttempt.Outcome.REFUSED, 503));
        when(subscriptionRepository.countByUserId(3L)).thenReturn(2L);

        PushSelfCheckResponse report = service.selfCheck();

        assertThat(report.pushEnabled()).isTrue();
        assertThat(report.publicKeyFingerprint()).isEqualTo("0123456789ab");
        assertThat(report.publicKeyMatchesPrivateKey()).isTrue();
        assertThat(report.subject()).isEqualTo("mailto:ops@nexus.test");
        assertThat(report.ttlHours()).isEqualTo(24L);
        assertThat(report.devices()).isEqualTo(2L);
        assertThat(report.lastAttempt().at()).isEqualTo(Instant.parse("2026-10-14T09:30:00Z"));
        assertThat(report.lastAttempt().attempted()).isEqualTo(2);
        assertThat(report.lastAttempt().accepted()).isEqualTo(1);
        // The outcome travels by name so it can be read without knowing the constants.
        assertThat(report.lastAttempt().outcome()).isEqualTo("REFUSED");
        assertThat(report.lastAttempt().status()).isEqualTo(503);

        // Whose devices these are is never in doubt.
        verify(subscriptionRepository).countByUserId(3L);
        verify(sender).lastAttempt(3L);
    }

    /**
     * Before anything has been sent, and with no key pair at all, the report says so
     * rather than filling the gaps with plausible values.
     */
    @Test
    void reportsWhatItCannotKnowAsAbsent() {
        when(userRepository.findByEmail("vidhi@nexus.com")).thenReturn(Optional.of(user(3L)));
        when(sender.isEnabled()).thenReturn(false);
        when(sender.publicKeyFingerprint()).thenReturn(null);
        when(sender.publicKeyMatchesPrivateKey()).thenReturn(null);
        when(subscriptionRepository.countByUserId(3L)).thenReturn(0L);

        PushSelfCheckResponse report = service.selfCheck();

        assertThat(report.pushEnabled()).isFalse();
        assertThat(report.publicKeyFingerprint()).isNull();
        // Null, not false: an unconfigured deployment has not been proven wrong.
        assertThat(report.publicKeyMatchesPrivateKey()).isNull();
        assertThat(report.devices()).isZero();
        assertThat(report.lastAttempt()).isNull();
    }

    /**
     * Reporting is not sending. A diagnostic endpoint that can prune subscriptions or
     * write rows is a diagnostic that can do damage when someone calls it repeatedly.
     */
    @Test
    void theSelfCheckOnlyReads() throws Exception {
        Transactional transactional = PushSubscriptionService.class
            .getMethod("selfCheck")
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private static void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private static User user(Long id) {
        User user = new User("Name " + id, "user" + id + "@nexus.com", "$2a$hash", UserRole.MEMBER);
        user.setId(id);
        return user;
    }
}
