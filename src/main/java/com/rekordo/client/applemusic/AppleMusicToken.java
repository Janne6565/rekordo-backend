package com.rekordo.client.applemusic;

import com.rekordo.configuration.AppleMusicProperties;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * The Apple Music developer token, minted here and kept until it is nearly due.
 *
 * <p>Apple does not issue this credential. It is a JWT signed with the ES256 key from the
 * developer portal, the same act of self-issuance {@code JwtService} performs for the
 * app's own tokens -- Apple verifies it against the public half it already holds, found
 * by the {@code kid} in the header.
 *
 * <p>The difference from every other secret here is the ceiling. Apple rejects a
 * developer token whose expiry is more than 180 days past its {@code iat}, so this one
 * cannot be read once and held: a pod up since spring would carry a token that expired in
 * autumn, and the only symptom is search answering 401 on a day nothing was deployed.
 *
 * <p>Hence <strong>parsed eagerly, minted lazily</strong>. The key is read in the
 * constructor, so a malformed secret fails startup where ArgoCD shows it rather than
 * surfacing as a 500 on the first search. The token is signed on demand and reused until
 * it is nearly due, so its life can never outlive the process holding it.
 */
@Slf4j
@Component
public class AppleMusicToken {

    /** Comfortably inside Apple's 180-day ceiling, with room for a clock that disagrees. */
    private static final Duration LIFETIME = Duration.ofDays(150);

    /** Re-mint this far ahead, so no request is ever the one that discovers the expiry. */
    private static final Duration REFRESH_MARGIN = Duration.ofDays(10);

    private final AppleMusicProperties properties;
    private final Clock clock;

    /** Null where the deployment carries no key, which local development routinely does. */
    private final PrivateKey key;

    /**
     * The token in hand, or null before the first mint.
     *
     * <p>Volatile because {@link #value()} reads it outside the lock that {@link #mint()}
     * writes it under. One reference rather than a token field beside an expiry field: a
     * single swap publishes both at once, so no reader can pair a fresh expiry with a
     * stale token. The same argument {@code ExternalRef} makes about merging, against
     * threads instead of devices.
     */
    private volatile Minted current;

    // Marked explicitly because there are two constructors: Spring picks neither on its
    // own and falls back to a no-arg one that does not exist. The second is the test seam.
    @Autowired
    public AppleMusicToken(AppleMusicProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AppleMusicToken(AppleMusicProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.key = properties.configured() ? readKey(properties.privateKey()) : null;
        if (this.key == null) {
            log.warn("No apple music key found; Album Search falls back to discogs");
        }
    }

    /**
     * The token to send, or empty when this deployment has no key.
     *
     * <p>Empty rather than a throw: a missing key is a local-development state, and the
     * caller can fall back to Discogs rather than failing a search outright.
     */
    public Optional<String> value() {
        if (key == null) {
            return Optional.empty();
        }
        Minted minted = current;
        return Optional.of(
            minted != null && !minted.dueWithin(REFRESH_MARGIN, clock.instant())
                ? minted.token()
                : mint().token()
        );
    }

    /**
     * Signs a fresh token, unless another thread got there first.
     *
     * <p>Synchronized, and re-checking {@code current} under the lock: twenty threads
     * noticing a due token in the same millisecond should produce one signature, not
     * twenty. Reads stay lock-free; only the rare mint serialises.
     */
    private synchronized Minted mint() {
        Instant now = clock.instant();

        Minted existing = current;
        if (existing != null && !existing.dueWithin(REFRESH_MARGIN, now)) {
            return existing;
        }

        Instant expiry = now.plus(LIFETIME);
        Minted fresh = new Minted(
            Jwts.builder()
                .header().keyId(properties.keyId()).and()
                .issuer(properties.teamId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.ES256)
                .compact(),
            expiry
        );
        log.info("Minted an Apple Music developer token, valid until {}", expiry);
        current = fresh;
        return fresh;
    }


    /**
     * Reads the PKCS#8 body of the .p8 straight from configuration.
     *
     * <p>Armour lines and whitespace are stripped rather than relied upon: a sealed secret
     * hands the key back with its newlines, an env var routinely flattens it to one line,
     * and both have to work.
     */
    private static PrivateKey readKey(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                "The Apple Music signing key is not a PKCS#8 EC key", e);
        }
    }

    /** One signed token and the moment it stops being usable, inseparable by construction. */
    private record Minted(String token, Instant expiresAt) {

        /**
         * Whether the renewal point has been reached or passed.
         *
         * <p>A negated {@code isAfter} rather than {@code isBefore} so the boundary instant
         * counts as due; the other way leaves a nanosecond that reports fresh.
         */
        boolean dueWithin(Duration margin, Instant now) {
            return !expiresAt.minus(margin).isAfter(now);
        }
    }
}
