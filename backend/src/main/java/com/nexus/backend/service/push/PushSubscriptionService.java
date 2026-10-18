package com.nexus.backend.service.push;

import com.nexus.backend.domain.push.PushSubscription;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.PushSelfCheckResponse;
import com.nexus.backend.dto.PushSubscriptionRequest;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.PushSubscriptionRepository;
import com.nexus.backend.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the list of browsers an account can be notified on: adding one when a
 * browser subscribes, dropping one when it is silenced, and standing in as the
 * "is push even possible here" answer for the client.
 *
 * <p>Every method is scoped to the signed-in user. A device is only ever added
 * for the caller and only ever removed by the account that owns it, because
 * otherwise knowing an endpoint would be enough to silence somebody else's
 * phone.
 */
@Service
public class PushSubscriptionService {

    /** Draft-format IPv4 literal, used to refuse an endpoint aimed at a private host. */
    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}$");

    private final PushSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final WebPushSender sender;

    public PushSubscriptionService(
        PushSubscriptionRepository subscriptionRepository,
        UserRepository userRepository,
        WebPushSender sender
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.sender = sender;
    }

    /** The key browsers subscribe with, or an empty string when push is off. */
    public String publicKey() {
        return sender.publicKey();
    }

    /**
     * Records this browser, or refreshes it if it is already known.
     *
     * <p>Re-registering the same endpoint is the normal case, not an exception:
     * the browser re-files its subscription on every load. An endpoint that is
     * already stored under a different account is moved to this one — the
     * endpoint belongs to the browser, and the browser now belongs to whoever
     * just signed in on it.
     */
    @Transactional
    public void subscribe(PushSubscriptionRequest request) {
        User me = currentUser();
        Long companyId = currentCompanyId();
        String endpoint = requireReachableEndpoint(request.endpoint());

        PushSubscription device = subscriptionRepository.findByEndpointAndCompanyId(
                endpoint, companyId).orElseGet(PushSubscription::new);

        if (device.getId() == null) {
            device.setUser(me);
            device.setCompanyId(companyId);
        } else if (!device.getUser().getId().equals(me.getId())) {
            Long previousOwnerId = device.getUser().getId();
            if (!previousOwnerId.equals(me.getId())) {
                pruneOtherOwner(previousOwnerId, endpoint);
            }
            device.setUser(me);
        }

        device.setEndpoint(endpoint);
        device.setP256dh(request.keys().p256dh().trim());
        device.setAuth(request.keys().auth().trim());
        subscriptionRepository.save(device);
    }

    /**
     * Silences one device. Removing something that is not there is success, not
     * an error: the caller asked for the outcome, and it already holds.
     *
     * <p>Scoped to the caller's company on purpose: an endpoint is only ever stored
     * for the caller, and the unsubscribe came from the caller, but a global lookup
     * would still match another company's row with the same endpoint — this refuses
     * to touch it.
     */
    @Transactional
    public void unsubscribe(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return;
        User me = currentUser();
        String candidate = endpoint.trim();
        subscriptionRepository.findByEndpointAndCompanyId(candidate, me.getCompanyId())
            .filter(device -> device.getUser().getId().equals(me.getId()))
            .ifPresent(subscriptionRepository::delete);
    }

    /**
     * Pushes a notification to the caller's own devices, so they can confirm push
     * works without waiting for somebody else to do something. Returns how many
     * devices the push service accepted.
     *
     * <p>Deliberately **not** {@code readOnly}: sending prunes subscriptions the
     * push service has disowned, and a read-only transaction would throw those
     * deletes away without a word — leaving the dead endpoint to be retried by
     * every notification that follows.
     */
    @Transactional
    public int sendTest() {
        User me = currentUser();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Nexus");
        payload.put("body", "Test notification — push is working on this device.");
        payload.put("url", "/");
        payload.put("tag", "nexus-push-test");

        return sender.sendToUser(me.getId(), me.getCompanyId(), payload);
    }

    /**
     * Removes the subscription that currently belongs to this browser under a
     * different user, so the re-registration path can create a clean one for the
     * current account.
     *
     * <p>This is the cleanup for the normal re-register path above: when the
     * browser re-files its subscription, {@code subscribe} reuses the existing row
     * instead of deleting and re-inserting it. When the stored row is the caller's
     * own but the caller is not the current account — i.e. the browser changed
     * hands — the row is orphaned under the previous owner and has to be removed so
     * the re-register can create a proper one.
     *
     * <p>The delete is company-scoped: a matching endpoint from another company is
     * not this caller's orphan and must not be removed for this caller.
     */
    @Transactional
    public void pruneOtherOwner(Long userId, String endpoint) {
        User me = currentUser();
        subscriptionRepository.findByUserIdAndEndpointAndCompanyId(
                userId, endpoint, me.getCompanyId())
            .filter(device -> !device.getUser().getId().equals(me.getId()))
            .ifPresent(device -> subscriptionRepository.deleteByIdAndCompanyId(
                device.getId(), me.getCompanyId()));
    }

    /**
     * Re-stamps the explicit company id on each of the caller's subscriptions
     * with the account's current company, and saves them.
     *
     * <p>A browser that moves from one company to another keeps physical access to
     * the same endpoint, so the endpoint id is not what makes it this workspace's —
     * the company on the row is. Re-stamping keeps the endpoint answering to this
     * company's send path and this company's unsubscribe path.
     */
    @Transactional
    public void restampCompanyOnDevices(User me) {
        for (PushSubscription device
                : subscriptionRepository.findByUserIdAndCompanyId(me.getId(), me.getCompanyId())) {
            device.setCompanyId(me.getCompanyId());
            subscriptionRepository.save(device);
        }
    }

    /**
     * Calls {@link #restampCompanyOnDevices(User)} for the given account. Public so
     * the notification service can re-stamp this account's devices when the account's
     * company changes.
     */
    public void restampCompanyOnDevicesForUser(Long userId) {
        User me = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        restampCompanyOnDevices(me);
    }

    /** How many browsers this account has registered; used by tests and diagnostics. */
    @Transactional(readOnly = true)
    public long deviceCount() {
        return subscriptionRepository.countByUserIdAndCompanyId(
                currentUser().getId(), currentUser().getCompanyId());
    }

    /**
     * What this deployment's push setup looks like from the inside, for the account
     * asking.
     *
     * <p>Read-only, and deliberately so: it inspects and reports, and a diagnostic
     * that writes is a diagnostic that can do damage. The send path already prunes
     * dead subscriptions inside its own transaction; nothing here needs to add to
     * that.
     *
     * <p>Everything in the answer is scoped to the caller. The key state and the
     * contact address are server-wide facts, but they are facts about a *public* key
     * and a public contact address — the private half is never described beyond
     * whether the public half belongs to it.
     */
    @Transactional(readOnly = true)
    public PushSelfCheckResponse selfCheck() {
        User me = currentUser();
        PushAttempt last = sender.lastAttempt(me.getId());

        return new PushSelfCheckResponse(
            sender.isEnabled(),
            sender.publicKeyFingerprint(),
            sender.publicKeyMatchesPrivateKey(),
            sender.subject(),
            sender.ttlHours(),
            subscriptionRepository.countByUserIdAndCompanyId(
                me.getId(), me.getCompanyId()),
            last == null ? null : new PushSelfCheckResponse.LastAttempt(
                last.at(),
                last.attempted(),
                last.accepted(),
                last.outcome().name(),
                last.status()));
    }

    /**
     * Refuses anything this server should not be willing to POST to.
     *
     * <p>The endpoint is supplied by a client and then fetched by the server, so
     * without a check it is a request forwarder: `http://169.254.169.254/…` would
     * make the application fetch cloud metadata on somebody else's behalf.
     * Real push endpoints are always https on a public hostname, so requiring
     * exactly that costs a legitimate caller nothing.
     */
    private static String requireReachableEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new ValidationException("A push endpoint is required");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("That is not a valid push endpoint");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()) {
            throw new ValidationException("A push endpoint must be an https URL");
        }
        if (!isPublicHost(host)) {
            throw new ValidationException("That push endpoint does not point at a public host");
        }
        // Trailing whitespace or a fragment would make the stored address differ
        // from the one the browser holds, and the row would never match again.
        return uri.toString();
    }

    private static boolean isPublicHost(String host) {
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.endsWith(".localhost")
            || lower.endsWith(".local") || lower.endsWith(".internal")) {
            return false;
        }
        // A literal IPv6 address is refused outright: no push service issues one,
        // and `[::1]` is the loopback case this check exists for.
        if (lower.startsWith("[") || lower.contains(":")) return false;

        Matcher ipv4 = IPV4.matcher(lower);
        if (ipv4.matches()) {
            int first = Integer.parseInt(ipv4.group(1));
            int second = Integer.parseInt(ipv4.group(2));
            if (first == 0 || first == 10 || first == 127) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && second == 168) return false;
            if (first == 169 && second == 254) return false;
        }
        return true;
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private Long currentCompanyId() {
        User me = currentUser();
        Long companyId = me.getCompanyId();
        if (companyId == null) {
            throw new ValidationException(
                "There is nobody else in the workspace to write to");
        }
        return companyId;
    }
}
