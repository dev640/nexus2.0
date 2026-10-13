package com.nexus.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push settings. All optional: without a key pair the application still
 * alerts open tabs over the socket and simply never sends a push, which is why
 * the feature can be deployed before the keys are.
 *
 * <p>The key pair is a VAPID pair — a plain P-256 EC key pair in the raw form
 * every Web Push tool emits: the public value is the 65-byte uncompressed point,
 * the private one is the 32-byte scalar, both base64url. The public half is
 * public by definition (the browser receives it) and the private half signs the
 * request; it must be a secret, because anyone holding it can send notifications
 * as this application.
 */
@ConfigurationProperties(prefix = "nexus.push")
public class PushProperties {

    /** Public half of the VAPID pair, base64url. Sent to browsers as they subscribe. */
    private String publicKey;

    /** Private half of the VAPID pair, base64url. Secret. */
    private String privateKey;

    /**
     * Contact for the push service, as a `mailto:` or `https:` URL. Push services
     * require it so they can reach an operator about a misbehaving sender.
     */
    private String subject = "mailto:ops@example.com";

    /** How long a push service should hold an undelivered notification, in hours. */
    private long ttlHours = 24;

    /** True when both halves of the key pair are present, so pushes can be sent. */
    public boolean isEnabled() {
        return notBlank(publicKey) && notBlank(privateKey);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public long getTtlHours() { return ttlHours; }
    public void setTtlHours(long ttlHours) { this.ttlHours = ttlHours; }
}
