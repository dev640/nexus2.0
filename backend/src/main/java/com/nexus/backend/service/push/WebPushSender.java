package com.nexus.backend.service.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.config.PushProperties;
import com.nexus.backend.domain.push.PushSubscription;
import com.nexus.backend.repository.PushSubscriptionRepository;
import com.zerodeplibs.webpush.VAPIDKeyPair;
import com.zerodeplibs.webpush.VAPIDKeyPairs;
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer;
import com.zerodeplibs.webpush.key.PrivateKeySources;
import com.zerodeplibs.webpush.key.PublicKeySources;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.crypto.KeyAgreement;

/**
 * Sends Web Push messages to the browsers an account has registered.
 *
 * <p>This is the half of the notification feature the socket cannot cover. A
 * live tab is told about new activity over the chat websocket; a phone with the
 * application closed has no socket, so the only way to reach it is a push
 * through the browser's push service, which is what this does.
 *
 * <p>Everything here is best-effort and non-fatal. Push is a convenience layered
 * on top of a notification that is already stored and already shown in the
 * Inbox, so a push service that is slow, down, or refusing this sender must
 * never fail the request that produced the notification. That is also why the
 * fan-out runs on its own thread: the caller is a write request that should not
 * wait on somebody else's network.
 */
@Service
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    /** One JWT per batch is plenty; the push services reject anything over 24h. */
    private static final int JWT_MINUTES = 15;

    /** A push service that has not answered by now is not worth the caller's time. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The whole exchange's deadline, not just the handshake. Distinct from the
     * connect timeout because a push service can accept the connection and then say
     * nothing at all, which no connect timeout covers.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How many characters of the key fingerprint are shown. Twelve hex characters
     * is about 48 bits: far too short to reconstruct a key from, and long enough
     * that two different keys never collide by accident.
     */
    private static final int FINGERPRINT_CHARS = 12;

    /**
     * How many accounts' most recent attempts are remembered. This is diagnostics,
     * not a history, so it is bounded — an unbounded map keyed by user id would be a
     * slow leak in a process that never restarts.
     */
    private static final int ATTEMPT_MEMORY = 512;

    /** No HTTP response was received, which is not a status and must not read as one. */
    private static final int NO_RESPONSE = 0;

    /**
     * Its own mapper rather than an injected one: this application has no
     * ObjectMapper bean (the chat socket builds its own for the same reason), and
     * the payload here is a flat map of strings, so there is nothing to configure.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PushProperties properties;
    private final PushSubscriptionRepository subscriptionRepository;
    private final HttpClient httpClient;

    /** Null when no key pair is configured, which switches the whole feature off. */
    private final VAPIDKeyPair vapidKeyPair;

    /**
     * Whether the two configured halves are one pair. Null when push is off, and
     * null when the check could not be made at all — an unverified pair is not a
     * wrong one.
     */
    private final Boolean publicKeyMatchesPrivateKey;

    /** A short digest of the key browsers are handed; null when push is off. */
    private final String publicKeyFingerprint;

    /** How long one exchange with a push service may take, in total. */
    private final Duration requestTimeout;

    /** The most recent attempt per account, least recently used evicted first. */
    private final Map<Long, PushAttempt> lastAttempts = Collections.synchronizedMap(
        new LinkedHashMap<>(ATTEMPT_MEMORY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, PushAttempt> eldest) {
                return size() > ATTEMPT_MEMORY;
            }
        });

    private final ExecutorService sender =
        Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "web-push-sender");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Annotated because of the second constructor below: with more than one, Spring
     * needs to be told which one wires the application up, and guessing wrong here
     * fails the whole context at startup rather than at a push.
     */
    @Autowired
    public WebPushSender(PushProperties properties, PushSubscriptionRepository subscriptionRepository) {
        this(properties, subscriptionRepository, REQUEST_TIMEOUT);
    }

    /**
     * The same sender with a stated deadline, so a test can wait for one instead of
     * waiting ten seconds. Production always takes the two-argument constructor.
     */
    WebPushSender(PushProperties properties, PushSubscriptionRepository subscriptionRepository,
                  Duration requestTimeout) {
        this.properties = properties;
        this.subscriptionRepository = subscriptionRepository;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        KeyPairState state = properties.isEnabled() ? readKeyPair(properties) : null;
        this.vapidKeyPair = state == null ? null : state.pair();
        this.publicKeyMatchesPrivateKey = state == null ? null : state.matchesPrivateKey();
        this.publicKeyFingerprint = state == null ? null : state.fingerprint();
        if (vapidKeyPair == null) {
            log.info("Web Push is off: no VAPID key pair configured, so only open tabs will alert");
        }
    }

    /** True when this deployment can reach a closed browser at all. */
    public boolean isEnabled() {
        return vapidKeyPair != null;
    }

    /**
     * The VAPID public key browsers must subscribe with, base64url. Empty when
     * push is off, which the client reads as "there is nothing to subscribe to"
     * rather than as an error.
     */
    public String publicKey() {
        if (vapidKeyPair == null) return "";
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(vapidKeyPair.extractPublicKeyInUncompressedForm());
    }

    /**
     * A short digest of the public key browsers subscribe with, or null when push
     * is off.
     *
     * <p>The full key is 87 characters of base64url, which nobody can compare in a
     * deployment checklist; twelve hex characters of its SHA-256 can be. It is the
     * public half on purpose — the browser is handed that value anyway, so a digest
     * of it reveals nothing new, whereas a digest of the private half would be a
     * fresh handle on the secret for every account that can read a self-check.
     *
     * <p>Together with {@link #publicKeyMatchesPrivateKey()} this identifies the
     * deployed pair exactly: a public key that matches the pair on disk *and* has a
     * private half belonging to it is that pair, not half of one.
     */
    public String publicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    /**
     * Whether the configured public key belongs to the configured private key, or
     * null when push is off or the check could not be made.
     *
     * <p>False is the interesting value: a pair assembled from two different
     * generations loads fine and then has every push refused with a 403, which looks
     * like the push service misbehaving rather than like a bad paste.
     */
    public Boolean publicKeyMatchesPrivateKey() {
        return publicKeyMatchesPrivateKey;
    }

    /** The contact address push services are told to use, for the self-check. */
    public String subject() {
        return properties.getSubject();
    }

    /** How long an undelivered push is held, in hours, for the self-check. */
    public long ttlHours() {
        return properties.getTtlHours();
    }

    /**
     * How the last attempt for this account ended, or null if there has not been
     * one since the process started.
     */
    public PushAttempt lastAttempt(Long userId) {
        return userId == null ? null : lastAttempts.get(userId);
    }

    /**
     * Sends one payload to every browser the account has registered and returns
     * how many the push service accepted.
     *
     * <p>Synchronous, so a caller that reports a result to a person can. Anything
     * on a request path should use {@link #sendToUserInBackground} instead.
     *
     * <p>This <em>writes</em>: a push service that answers 404 or 410 means the
     * browser is gone, and the row is deleted. The caller must therefore not be in
     * a read-only transaction — there the delete is discarded silently, leaving a
     * dead endpoint to be retried on every future notification. That is not
     * hypothetical: the test-notification endpoint was marked read-only and the
     * pruning vanished until an end-to-end run caught it.
     */
    public int sendToUser(Long userId, Long companyId, Map<String, Object> payload) {
        if (vapidKeyPair == null || userId == null || companyId == null) return 0;

        List<PushSubscription> devices = subscriptionRepository.findByUserIdAndCompanyId(
            userId, companyId);

        // Defensive scope check: the repository query already answers for one company,
        // but a wrong company id here would silently fan out to the wrong workspace.
        // Drop anything that does not belong to this caller's company before sending.
        List<PushSubscription> inScope = new java.util.ArrayList<>();
        for (PushSubscription device : devices) {
            if (device.getCompanyId() != null
                && device.getCompanyId().equals(companyId)
                && device.getUser().getId().equals(userId)) {
                inScope.add(device);
            }
        }

        // Nothing registered is not a failed attempt — there was nowhere to send —
        // and recording it would wipe the diagnostics of the last real send.
        if (inScope.isEmpty()) return 0;

        byte[] body;
        try {
            body = JSON.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize a push payload: {}", e.getMessage());
            remember(userId, inScope.size(), 0, PushAttempt.Outcome.UNSENDABLE, NO_RESPONSE);
            return 0;
        }

        int delivered = 0;
        Result lastFailure = null;
        Result last = null;
        for (PushSubscription device : inScope) {
            last = send(device, body);
            if (last.accepted()) delivered++;
            else lastFailure = last;
        }

        // The most recent refusal is more use than the last device's luck: with two
        // browsers and one refused, the refusal is the thing to act on.
        Result reported = lastFailure != null ? lastFailure : last;
        remember(userId, inScope.size(), delivered, reported.outcome(), reported.status());
        return delivered;
    }

    /**
     * The same send, off the caller's thread. Used when a notification is created:
     * the person who caused it should not wait on Google's or Apple's response,
     * and a failure is not theirs to see.
     */
    public void sendToUserInBackground(Long userId, Long companyId, Map<String, Object> payload) {
        if (vapidKeyPair == null || userId == null || companyId == null) return;
        sender.execute(() -> sendToUser(userId, companyId, payload));
    }

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }

    private Result send(PushSubscription device, byte[] body) {
        HttpRequest request;
        try {
            request = StandardHttpClientRequestPreparer.getBuilder()
                .pushSubscription(toLibrarySubscription(device))
                .vapidJWTExpiresAfter(JWT_MINUTES, TimeUnit.MINUTES)
                .vapidJWTSubject(properties.getSubject())
                .pushMessage(body)
                .ttl(properties.getTtlHours(), TimeUnit.HOURS)
                // A notification is the reason the user picked the device up, not
                // something to defer until it is idle.
                .urgencyHigh()
                .build(vapidKeyPair)
                .toRequest();
        } catch (RuntimeException e) {
            log.warn("Could not build a push request for subscription {}: {}", device.getId(), e.getMessage());
            return new Result(false, PushAttempt.Outcome.NOT_SENT, NO_RESPONSE);
        }

        try {
            // Through a future rather than HttpClient.send, because a built request
            // carries no deadline of its own: without one, a push service that
            // accepts the connection and then says nothing leaves this thread waiting
            // forever. That is not a rare shape of failure on a bad day at Google —
            // and the fan-out runs on a single thread, so one wedged request would
            // stall every notification behind it while a caller pressing "send a
            // test" waited with it.
            CompletableFuture<HttpResponse<Void>> pending =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());

            HttpResponse<Void> response;
            try {
                response = pending.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Give up on the exchange rather than wait for a reply that is not
                // coming. The push service still holds the message until its TTL, so
                // a slow delivery can still land; this only stops the sender.
                pending.cancel(true);
                log.warn("Push delivery to subscription {} timed out after {}s",
                    device.getId(), requestTimeout.toSeconds());
                return new Result(false, PushAttempt.Outcome.UNREACHABLE, NO_RESPONSE);
            }

            int status = response.statusCode();

            if (status == 404 || status == 410) {
                // The push service is saying the browser is gone — uninstalled, or
                // its subscription replaced. Keeping the row would mean retrying a
                // dead address forever. Logged rather than silent, because an
                // operator watching a push fail deserves to see why it stopped.
                Long deviceCompanyId = device.getCompanyId();
                if (deviceCompanyId == null) {
                    subscriptionRepository.deleteById(device.getId());
                } else {
                    subscriptionRepository.deleteByIdAndCompanyId(device.getId(), deviceCompanyId);
                }
                log.info("Dropped push subscription {}: the push service reports it is gone (HTTP {})",
                    device.getId(), status);
                return new Result(false, PushAttempt.Outcome.GONE, status);
            }
            if (status >= 200 && status < 300) {
                return new Result(true, PushAttempt.Outcome.ACCEPTED, status);
            }

            // 413 (payload too large), 429 (rate limited) and 5xx are all "not now",
            // not "never": the subscription stays and the next alert tries again.
            log.warn("Push service refused a notification for subscription {}: HTTP {}", device.getId(), status);
            return new Result(false, PushAttempt.Outcome.REFUSED, status);
        } catch (ExecutionException e) {
            // A transport failure arrives wrapped once the send is asynchronous.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.warn("Push delivery to subscription {} failed: {}", device.getId(), cause.getMessage());
            return new Result(false, PushAttempt.Outcome.UNREACHABLE, NO_RESPONSE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, PushAttempt.Outcome.UNREACHABLE, NO_RESPONSE);
        }
    }

    /**
     * Records how an attempt ended, for {@link #lastAttempt(Long)} and the
     * self-check that reads it.
     */
    private void remember(Long userId, int attempted, int accepted, PushAttempt.Outcome outcome, int status) {
        lastAttempts.put(userId, new PushAttempt(Instant.now(), attempted, accepted, outcome, status));
    }

    /** How one browser's send ended — the raw material of an attempt summary. */
    private record Result(boolean accepted, PushAttempt.Outcome outcome, int status) {}

    /** The configured pair, plus what could be proven about it. */
    private record KeyPairState(VAPIDKeyPair pair, Boolean matchesPrivateKey, String fingerprint) {}

    /**
     * The stored row as the library's own subscription type.
     *
     * <p>The library type is spelled out in full because this project has a
     * {@code PushSubscription} of its own — the row — and importing the other one
     * would hide it. Here, at the single point where the two meet, the ambiguity
     * is worth being explicit about.
     */
    private static com.zerodeplibs.webpush.PushSubscription toLibrarySubscription(PushSubscription device) {
        com.zerodeplibs.webpush.PushSubscription.Keys keys =
            new com.zerodeplibs.webpush.PushSubscription.Keys();
        keys.setP256dh(device.getP256dh());
        keys.setAuth(device.getAuth());

        com.zerodeplibs.webpush.PushSubscription subscription =
            new com.zerodeplibs.webpush.PushSubscription();
        subscription.setEndpoint(device.getEndpoint());
        subscription.setKeys(keys);
        return subscription;
    }

    /**
     * Builds the signing pair from the configured raw values.
     *
     * <p>The public half is already in the form the library wants — the 65-byte
     * uncompressed point, exactly what a browser takes as
     * {@code applicationServerKey}. The private half is a bare 32-byte scalar, so
     * it is wrapped in the curve's parameters with the JCA rather than parsed out
     * of a PKCS#8 envelope, which is what "just the scalar" would look like.
     *
     * <p>A bad key pair logs and disables push instead of failing startup. A
     * deployment whose keys are wrong still runs; it just does not push.
     *
     * <p>The pair is also checked against itself here — see
     * {@link #publicMatchesPrivate} — because two halves from different generations
     * parse perfectly well and only fail at the push service, as a 403 that reads
     * like the service's fault.
     */
    private static KeyPairState readKeyPair(PushProperties properties) {
        try {
            byte[] publicBytes = decodeKey(properties.getPublicKey());
            byte[] privateBytes = decodeKey(properties.getPrivateKey());

            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);

            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            ECPrivateKey privateKey = (ECPrivateKey) keyFactory.generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, privateBytes), curve));

            Boolean matches = publicMatchesPrivate(publicBytes, privateKey);
            if (Boolean.FALSE.equals(matches)) {
                log.warn("The VAPID public key does not belong to the configured private key: "
                    + "every push will be refused with HTTP 403 until both halves come from one generation");
            }

            return new KeyPairState(
                VAPIDKeyPairs.of(
                    PrivateKeySources.ofECPrivateKey(privateKey),
                    PublicKeySources.ofUncompressedBytes(publicBytes)),
                matches,
                fingerprint(publicBytes));
        } catch (Exception e) {
            log.warn("VAPID key pair could not be read, so Web Push stays off: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Whether the public half actually belongs to the private half.
     *
     * <p>Both halves are only numbers to a parser, so a pair assembled from two
     * generations loads happily and fails later and elsewhere: the push service
     * rejects the signature with a 403, which looks like a service problem rather
     * than a bad paste. Multiplying the private scalar by the curve's generator gives
     * the public key that scalar defines, and that is the one comparison which
     * settles it. ECDH against the generator as the peer key performs exactly that
     * multiplication, using nothing outside the JDK.
     *
     * <p>Returns null when the question cannot be answered — the public value is not
     * an uncompressed point, or this JVM will not do the arithmetic. "Unverified" is
     * reported as itself and never as a mismatch.
     */
    private static Boolean publicMatchesPrivate(byte[] publicBytes, ECPrivateKey privateKey) {
        if (publicBytes.length != 65 || publicBytes[0] != 0x04) return null;
        try {
            ECParameterSpec curve = privateKey.getParams();
            ECPublicKey generatorPoint = (ECPublicKey) KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(curve.getGenerator(), curve));

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(privateKey);
            agreement.doPhase(generatorPoint, true);

            // The shared x coordinate is that of d·G — the public key this scalar
            // defines. Compensating for the sign of y is not needed: Web Push keys
            // travel in compressed-free form, and the browser subscribed with the
            // public key exactly as configured.
            BigInteger derivedX = new BigInteger(1, agreement.generateSecret());
            BigInteger configuredX = new BigInteger(1, Arrays.copyOfRange(publicBytes, 1, 33));
            return derivedX.equals(configuredX);
        } catch (Exception e) {
            log.warn("Could not check the VAPID pair against itself: {}", e.getMessage());
            return null;
        }
    }

    /**
     * A short, printable digest of the public key: the form an operator can compare
     * against the pair on disk, where 87 characters of base64url is not.
     */
    private static String fingerprint(byte[] publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
            StringBuilder hex = new StringBuilder(FINGERPRINT_CHARS);
            for (int i = 0; hex.length() < FINGERPRINT_CHARS; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; a missing one costs a diagnostic, not push.
            log.warn("Could not fingerprint the VAPID public key: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Decodes a key written in either base64 alphabet.
     *
     * <p>The values come from whatever tool the operator used, and they differ:
     * {@code web-push} and every browser-side example emit base64url, while a key
     * copied out of other tooling is often standard base64. Accepting both costs
     * nothing and removes a footgun that produces an unhelpful parse error.
     */
    private static byte[] decodeKey(String value) {
        String normalized = value.trim().replace('-', '+').replace('_', '/').replaceAll("\\s", "");
        int padding = (4 - normalized.length() % 4) % 4;
        return Base64.getDecoder().decode(normalized + "=".repeat(padding));
    }
}
