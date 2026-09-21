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

@Slf4j
@Component
public class AppleMusicToken {

    private static final Duration LIFETIME = Duration.ofDays(150);
    private static final Duration REFRESH_MARGIN = Duration.ofDays(10);

    private final AppleMusicProperties properties;
    private final Clock clock;

    private final PrivateKey key;

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
        log.info("Minted new apple music token");
        current = fresh;
        return fresh;
    }


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

    private record Minted(String token, Instant expiresAt) {
        boolean dueWithin(Duration margin, Instant now) {
            return !expiresAt.minus(margin).isAfter(now);
        }
    }
}
