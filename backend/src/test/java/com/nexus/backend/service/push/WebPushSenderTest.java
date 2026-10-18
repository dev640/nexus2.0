package com.nexus.backend.service.push;

import com.nexus.backend.config.PushProperties;
import com.nexus.backend.domain.push.PushSubscription;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.PushSubscriptionRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves the whole sending path, end to end, against a stub standing in for the
 * push service.
 *
 * <p>Asserting that a request was made would be weak: the entire point of Web
 * Push is that the payload is encrypted to the browser's key and signed for the
 * push service, and a request with the headers in the wrong shape, or a payload
 * the browser cannot decrypt, looks exactly like a working one from the sender's
 * side. So this test plays the browser: it generates the key pair a browser would
 * register, decrypts what arrives with the browser's own private key, and
 * asserts the sentence that comes out is the notification that went in.
 *
 * <p>The decryption is written out longhand — ECDH for the shared secret, HKDF
 * for the keys, AES-GCM for the record — rather than delegated to the library
 * under test. A test that decrypts with the same code that encrypted proves only
 * self-consistency.
 */
class WebPushSenderTest {

    /** The VAPID pair the application is configured with, as an operator would supply it. */
    private static String vapidPublic;
    private static String vapidPrivate;

    /** The key pair a browser generates when it subscribes, which only it can decrypt for. */
    private KeyPair browserKeys;
    private String browserP256dh;
    private String browserAuth;

    private PushProperties properties;
    private PushSubscriptionRepository repository;
    private WebPushSender sender;

    private HttpServer pushService;
    private final List<CapturedRequest> received = new ArrayList<>();
    private final AtomicInteger respondWith = new AtomicInteger(201);

    private record CapturedRequest(Map<String, String> headers, byte[] body) {}

    @BeforeEach
    void setUp() throws Exception {
        KeyPair vapid = generateKeyPair();
        vapidPublic = base64Url(uncompressedPoint((ECPublicKey) vapid.getPublic()));
        vapidPrivate = base64Url(scalarOf((ECPrivateKey) vapid.getPrivate()));

        browserKeys = generateKeyPair();
        browserP256dh = base64Url(uncompressedPoint((ECPublicKey) browserKeys.getPublic()));
        browserAuth = base64Url(randomBytes(16));

        received.clear();
        respondWith.set(201);

        pushService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pushService.createContext("/push", this::handle);
        pushService.start();

        properties = new PushProperties();
        properties.setPublicKey(vapidPublic);
        properties.setPrivateKey(vapidPrivate);
        properties.setSubject("mailto:ops@nexus.test");
        properties.setTtlHours(24);

        repository = mock(PushSubscriptionRepository.class);
        sender = new WebPushSender(properties, repository);
    }

    @AfterEach
    void tearDown() {
        sender.shutdown();
        pushService.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) headers.put(name.toLowerCase(), values.get(0));
        });
        CapturedRequest captured = new CapturedRequest(headers, exchange.getRequestBody().readAllBytes());
        synchronized (received) {
            received.add(captured);
        }
        exchange.sendResponseHeaders(respondWith.get(), -1);
        exchange.close();
    }

    @Test
    void deliversANotificationTheBrowserCanDecrypt() throws Exception {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Nexus");
        payload.put("body", "Palak assigned you \"Ship the release\"");
        payload.put("url", "/board?task=42");
        payload.put("tag", "nexus-notification-12");

        assertThat(sender.sendToUser(7L, 1L, payload)).isEqualTo(1);

        CapturedRequest request = onlyRequest();
        // Signed for the push service with the configured key, and addressed to
        // the browser's key rather than the server's.
        assertThat(request.headers().get("authorization")).startsWith("vapid t=").contains("k=");
        assertThat(request.headers().get("content-encoding")).isEqualTo("aes128gcm");
        // The TTL header is in seconds (RFC 8030), so 24 hours on the wire.
        assertThat(request.headers().get("ttl")).isEqualTo("86400");
        assertThat(request.headers().get("urgency")).isEqualTo("high");
        assertThat(request.headers().get("content-type")).isEqualTo("application/octet-stream");

        String plaintext = decryptAsTheBrowserWould(request);
        assertThat(plaintext)
            .contains("\"title\":\"Nexus\"")
            .contains("Palak assigned you \\\"Ship the release\\\"")
            // The path an alert click needs, so a push can open the work itself.
            .contains("\"/board?task=42\"");
    }

    @Test
    void servesThePublicKeyInTheFormABrowserSubscribesWith() {
        assertThat(sender.isEnabled()).isTrue();
        assertThat(sender.publicKey()).isEqualTo(vapidPublic);

        byte[] raw = decodeBase64Url(sender.publicKey());
        // 65 bytes, 0x04 prefix: an uncompressed point. Anything else is rejected
        // by pushManager.subscribe without a useful message.
        assertThat(raw).hasSize(65);
        assertThat(raw[0]).isEqualTo((byte) 0x04);
    }

    @Test
    void prunesADeviceThePushServiceSaysIsGone() {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device()));
        respondWith.set(410);

        assertThat(sender.sendToUser(7L, 1L, Map.of("body", "gone"))).isZero();

        verify(repository).deleteByIdAndCompanyId(31L, 1L);
    }

    @Test
    void keepsADeviceWhenThePushServiceIsMerelyBusy() {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device()));
        respondWith.set(503);

        assertThat(sender.sendToUser(7L, 1L, Map.of("body", "not now"))).isZero();

        // A 5xx is "try again", not "this browser is gone": dropping the row would
        // silence a working phone until its next re-subscribe.
        verify(repository, never()).deleteById(31L);
    }

    @Test
    void doesNothingWhenNoKeyPairIsConfigured() {
        PushProperties unconfigured = new PushProperties();
        WebPushSender off = new WebPushSender(unconfigured, repository);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.publicKey()).isEmpty();
        assertThat(off.sendToUser(7L, 1L, Map.of("body", "x"))).isZero();
        assertThat(received).isEmpty();
        off.shutdown();
    }

    @Test
    void readsAKeyPairWrittenInEitherBase64Alphabet() {
        PushProperties standardAlphabet = new PushProperties();
        standardAlphabet.setPublicKey(vapidPublic);
        // A key pasted from tooling that emits standard base64 with padding.
        standardAlphabet.setPrivateKey(Base64.getEncoder().encodeToString(decodeBase64Url(vapidPrivate)));
        standardAlphabet.setSubject("mailto:ops@nexus.test");

        WebPushSender tolerant = new WebPushSender(standardAlphabet, repository);

        assertThat(tolerant.isEnabled()).isTrue();
        assertThat(tolerant.publicKey()).isEqualTo(vapidPublic);
        tolerant.shutdown();
    }

    @Test
    void aBrokenKeyPairSwitchesPushOffInsteadOfFailingStartup() {
        PushProperties broken = new PushProperties();
        broken.setPublicKey("not-a-key");
        broken.setPrivateKey("also-not-a-key");

        WebPushSender off = new WebPushSender(broken, repository);

        assertThat(off.isEnabled()).isFalse();
        off.shutdown();
    }

    /**
     * The fingerprint identifies the key browsers are actually handed, which is the
     * only thing worth comparing against the pair an operator has on disk.
     */
    @Test
    void fingerprintsThePublicKeyBrowsersAreGiven() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(decodeBase64Url(sender.publicKey()));

        // Hex by a second route on purpose: the point of this assertion is which
        // bytes went in, and reusing the sender's own formatting would hide a mix-up
        // between the digest and its rendering.
        StringBuilder expected = new StringBuilder();
        for (byte b : digest) {
            expected.append(Character.forDigit((b >> 4) & 0xf, 16));
            expected.append(Character.forDigit(b & 0xf, 16));
        }

        assertThat(sender.publicKeyFingerprint()).hasSize(12).isEqualTo(expected.substring(0, 12));
        assertThat(sender.publicKeyMatchesPrivateKey()).isTrue();
    }

    /**
     * Two halves from different generations parse perfectly well and then fail at the
     * push service, as a 403 nobody can explain. The application has to be able to say
     * so about itself.
     */
    @Test
    void noticesWhenThePublicHalfBelongsToAnotherPair() throws Exception {
        KeyPair other = generateKeyPair();
        PushProperties halfSwapped = new PushProperties();
        halfSwapped.setPublicKey(base64Url(uncompressedPoint((ECPublicKey) other.getPublic())));
        halfSwapped.setPrivateKey(vapidPrivate);
        halfSwapped.setSubject("mailto:ops@nexus.test");

        WebPushSender mismatched = new WebPushSender(halfSwapped, repository);
        try {
            assertThat(mismatched.publicKeyMatchesPrivateKey()).isFalse();
            // Still enabled, deliberately: that public key is a real one and a browser
            // can subscribe with it, so the deployment keeps working and the self-check
            // is what tells an operator the pair is wrong. Going dark would replace a
            // diagnosable problem with a silent one.
            assertThat(mismatched.isEnabled()).isTrue();
        } finally {
            mismatched.shutdown();
        }
    }

    @Test
    void remembersHowTheLastAttemptEnded() {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device()));

        assertThat(sender.lastAttempt(7L)).as("nothing sent yet").isNull();
        sender.sendToUser(7L, 1L, Map.of("body", "hello"));

        PushAttempt attempt = sender.lastAttempt(7L);
        assertThat(attempt).isNotNull();
        assertThat(attempt.at()).isNotNull();
        assertThat(attempt.attempted()).isEqualTo(1);
        assertThat(attempt.accepted()).isEqualTo(1);
        assertThat(attempt.outcome()).isEqualTo(PushAttempt.Outcome.ACCEPTED);
        assertThat(attempt.status()).isEqualTo(201);
        // One account's diagnostics are never another account's.
        assertThat(sender.lastAttempt(8L)).isNull();
    }

    @Test
    void remembersADeviceThePushServiceHasDisowned() {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device()));
        respondWith.set(410);

        sender.sendToUser(7L, 1L, Map.of("body", "gone"));

        PushAttempt attempt = sender.lastAttempt(7L);
        assertThat(attempt.outcome()).isEqualTo(PushAttempt.Outcome.GONE);
        assertThat(attempt.status()).isEqualTo(410);
        assertThat(attempt.accepted()).isZero();
    }

    /**
     * A push service that says nothing at all is the case a log is worst at: the
     * request hangs or the connection is refused, and the only trace is a warning from
     * a background thread.
     */
    @Test
    void remembersSilenceFromThePushServiceAsUnreachable() throws Exception {
        when(repository.findByUserIdAndCompanyId(7L, 1L))
            .thenReturn(List.of(deviceAt(44L, "http://127.0.0.1:" + closedPort() + "/push")));

        assertThat(sender.sendToUser(7L, 1L, Map.of("body", "anyone there"))).isZero();

        PushAttempt attempt = sender.lastAttempt(7L);
        assertThat(attempt.outcome()).isEqualTo(PushAttempt.Outcome.UNREACHABLE);
        // No response, so no status — and 0 must not be mistaken for one.
        assertThat(attempt.status()).isZero();
    }

    /**
     * A push service that takes the connection and then goes quiet.
     *
     * <p>The deadline is the entire point of this test. Nothing else catches this
     * shape of failure: the connect timeout is satisfied the moment the handshake
     * completes, so without a deadline of its own the sender waits for a reply that
     * never comes — for as long as the process lives. The fan-out is single-threaded,
     * so one such request stalls every notification queued behind it, and the same
     * send happens on the request thread when someone presses "send a test".
     */
    @Test
    void givesUpOnAPushServiceThatNeverAnswers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        pushService.createContext("/silent", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        WebPushSender impatient = new WebPushSender(properties, repository, Duration.ofMillis(250));
        try {
            when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(
                deviceAt(46L, "http://127.0.0.1:" + pushService.getAddress().getPort() + "/silent")));

            long startedAt = System.nanoTime();
            assertThat(impatient.sendToUser(7L, 1L, Map.of("body", "still there?"))).isZero();
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            PushAttempt attempt = impatient.lastAttempt(7L);
            assertThat(attempt.outcome()).isEqualTo(PushAttempt.Outcome.UNREACHABLE);
            assertThat(attempt.status()).isZero();
            // Bounded by the deadline, not by the push service's goodwill.
            assertThat(elapsedMillis).isLessThan(5_000L);
        } finally {
            release.countDown();
            impatient.shutdown();
        }
    }

    /**
     * With one device taken and one refused, the summary has to describe the refusal.
     * Reporting the device that worked would say "accepted" while a phone stayed
     * silent, which is exactly the confusion the self-check exists to remove.
     */
    @Test
    void reportsTheRefusalWhenOnlySomeDevicesTookIt() throws Exception {
        PushSubscription broken = deviceAt(45L, "http://127.0.0.1:" + closedPort() + "/push");
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of(device(), broken));

        assertThat(sender.sendToUser(7L, 1L, Map.of("body", "two phones"))).isEqualTo(1);

        PushAttempt attempt = sender.lastAttempt(7L);
        assertThat(attempt.attempted()).isEqualTo(2);
        assertThat(attempt.accepted()).isEqualTo(1);
        assertThat(attempt.outcome()).isEqualTo(PushAttempt.Outcome.UNREACHABLE);
    }

    @Test
    void hasNothingToRememberWhenNoDeviceIsRegistered() {
        when(repository.findByUserIdAndCompanyId(7L, 1L)).thenReturn(List.of());

        assertThat(sender.sendToUser(7L, 1L, Map.of("body", "nobody home"))).isZero();

        // There was no attempt to remember, and inventing one would erase the record
        // of the last send that did happen.
        assertThat(sender.lastAttempt(7L)).isNull();
    }

    /** A stub user the push tests need before any user row exists. */
    private static User user(Long id) {
        User user = new User("Name " + id, "user" + id + "@nexus.com", "$2a$hash", UserRole.MEMBER);
        user.setId(id);
        user.setCompanyId(1L);
        return user;
    }

    private PushSubscription device() {
        PushSubscription device = new PushSubscription();
        device.setId(31L);
        device.setEndpoint("http://127.0.0.1:" + pushService.getAddress().getPort() + "/push");
        device.setP256dh(browserP256dh);
        device.setAuth(browserAuth);
        device.setCompanyId(1L);
        device.setUser(user(7L));
        return device;
    }

    private PushSubscription deviceAt(long id, String endpoint) {
        PushSubscription device = new PushSubscription();
        device.setId(id);
        device.setEndpoint(endpoint);
        device.setP256dh(browserP256dh);
        device.setAuth(browserAuth);
        device.setCompanyId(1L);
        device.setUser(user(7L));
        return device;
    }

    /**
     * A port nothing is listening on: bound, then released.
     *
     * <p>A plain socket rather than an `HttpServer`, deliberately. A server created
     * and then stopped without ever being started can leave the port bound and
     * listening, which accepts the connection and answers nothing — that is a hang,
     * not a refusal, and it exercises none of the refused-connection path.
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private CapturedRequest onlyRequest() {
        synchronized (received) {
            assertThat(received).hasSize(1);
            return received.get(0);
        }
    }

    // ---------- the receiving side, as a browser implements it (RFC 8188/8291) ----------

    /** Runs the browser's half of RFC 8291 over what arrived, and returns the JSON. */
    private String decryptAsTheBrowserWould(CapturedRequest request) throws Exception {
        byte[] body = request.body();
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int recordSize = readInt(body, 16);
        int keyIdLength = body[20] & 0xff;
        byte[] keyId = Arrays.copyOfRange(body, 21, 21 + keyIdLength);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + keyIdLength, body.length);

        // The framing a browser relies on. With aes128gcm the header block — salt,
        // record size and the server's public key as the keyid — travels inside the
        // body rather than in an Encryption header (RFC 8188 §2.1), so there is
        // nothing to check on the wire beyond the content coding itself.
        assertThat(recordSize).isGreaterThanOrEqualTo(18);
        assertThat(keyId).hasSize(65);
        assertThat(keyId[0]).as("uncompressed point").isEqualTo((byte) 0x04);
        // Nothing recoverable from the bytes on the wire: the feature is pointless
        // if the push service can read the notification.
        assertThat(new String(body, StandardCharsets.ISO_8859_1))
            .doesNotContain("Ship the release");

        // ikm = ECDH(browser private, server public), where the server's public
        // key is the one it put in the body.
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init((PrivateKey) browserKeys.getPrivate());
        agreement.doPhase(publicKeyFromUncompressed(keyId), true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] authSecret = decodeBase64Url(browserAuth);
        byte[] prkKey = hkdfExtract(authSecret, sharedSecret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
            concat(decodeBase64Url(browserP256dh), keyId));
        byte[] inputKeyingMaterial = hkdfExpand(prkKey, keyInfo, 32);

        byte[] prk = hkdfExtract(salt, inputKeyingMaterial);
        byte[] contentKey = hkdfExpand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdfExpand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(ciphertext);

        // The last octet is the record delimiter (0x02); everything before it is
        // the message, minus any zero padding.
        int end = padded.length - 1;
        while (end >= 0 && padded[end] == 0) end--;
        assertThat(padded[end]).as("record padding delimiter").isEqualTo((byte) 0x02);
        return new String(padded, 0, end, StandardCharsets.UTF_8);
    }

    // ---------- small helpers ----------

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] uncompressedPoint(ECPublicKey key) {
        byte[] x = toFixedLength(key.getW().getAffineX(), 32);
        byte[] y = toFixedLength(key.getW().getAffineY(), 32);
        return concat(new byte[] {0x04}, concat(x, y));
    }

    private static byte[] scalarOf(ECPrivateKey key) {
        return toFixedLength(key.getS(), 32);
    }

    private static ECPublicKey publicKeyFromUncompressed(byte[] raw) throws Exception {
        java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        java.security.spec.ECParameterSpec curve =
            parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);

        java.security.spec.ECPoint point = new java.security.spec.ECPoint(
            new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
            new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) java.security.KeyFactory.getInstance("EC")
            .generatePublic(new java.security.spec.ECPublicKeySpec(point, curve));
    }

    /** Big-endian, left-padded to a fixed width — the form Web Push keys travel in. */
    private static byte[] toFixedLength(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) return bytes;
        byte[] fixed = new byte[length];
        if (bytes.length > length) {
            // A leading zero sign byte, dropped.
            System.arraycopy(bytes, bytes.length - length, fixed, 0, length);
        } else {
            System.arraycopy(bytes, 0, fixed, length - bytes.length, bytes.length);
        }
        return fixed;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] hkdfExtract(byte[] salt, byte[] inputKeyingMaterial) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        return mac.doFinal(inputKeyingMaterial);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] output = new byte[0];
        byte[] block = new byte[0];
        for (int counter = 1; output.length < length; counter++) {
            mac.reset();
            mac.update(block);
            mac.update(info);
            mac.update((byte) counter);
            block = mac.doFinal();
            output = concat(output, block);
        }
        return Arrays.copyOf(output, length);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
            | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64Url(String value) {
        String normalized = value.replace('-', '+').replace('_', '/');
        int padding = (4 - normalized.length() % 4) % 4;
        return Base64.getDecoder().decode(normalized + "=".repeat(padding));
    }
}
