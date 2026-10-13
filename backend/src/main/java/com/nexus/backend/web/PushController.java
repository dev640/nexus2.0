package com.nexus.backend.web;

import com.nexus.backend.dto.PushSelfCheckResponse;
import com.nexus.backend.dto.PushSubscriptionRequest;
import com.nexus.backend.dto.PushUnsubscribeRequest;
import com.nexus.backend.service.push.PushSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Device registration for notifications that arrive while the application is
 * closed.
 *
 * <p>Every operation here is about the caller's own devices, so there is no path
 * parameter and nothing to authorize beyond being signed in: the account comes from
 * the security context, never from the request body. That includes the self-check,
 * which reports the caller's own registrations and the server's key *state* — never
 * another account's devices, and never key material.
 */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushSubscriptionService pushSubscriptionService;

    public PushController(PushSubscriptionService pushSubscriptionService) {
        this.pushSubscriptionService = pushSubscriptionService;
    }

    /**
     * The VAPID public key a browser needs to create a subscription. Served
     * rather than built into the frontend so the key has one home: it has to
     * match the private half the server signs with, and two copies drift.
     */
    @GetMapping("/public-key")
    public ResponseEntity<Map<String, String>> publicKey() {
        return ResponseEntity.ok(Map.of("publicKey", pushSubscriptionService.publicKey()));
    }

    /** This browser wants notifications. Idempotent: it is re-sent on every load. */
    @PostMapping("/subscriptions")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        pushSubscriptionService.subscribe(request);
        return ResponseEntity.noContent().build();
    }

    /** This browser has had enough. Its own devices only. */
    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@Valid @RequestBody PushUnsubscribeRequest request) {
        pushSubscriptionService.unsubscribe(request.endpoint());
        return ResponseEntity.noContent().build();
    }

    /**
     * Sends a notification to the caller's own devices and reports how many were
     * accepted. The only way to prove push reaches a phone without waiting for
     * somebody else to touch the workspace.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Integer>> test() {
        return ResponseEntity.ok(Map.of("devices", pushSubscriptionService.sendTest()));
    }

    /**
     * What push looks like from inside this deployment, for the caller.
     *
     * <p>Read-only, and the fastest way to settle the two questions a failed push
     * raises: is the server signing with the pair we think it is, and did it even
     * try? The fingerprint is a digest of the public key — compare it against the
     * pair on disk — and the last attempt is what the push service last said about
     * this account's browsers.
     */
    @GetMapping("/self-check")
    public ResponseEntity<PushSelfCheckResponse> selfCheck() {
        return ResponseEntity.ok(pushSubscriptionService.selfCheck());
    }
}
