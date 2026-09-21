package com.rekordo.client.applemusic;

import com.rekordo.configuration.AppleMusicProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The credential Apple will not issue for us, and the ceiling it is minted against.
 *
 * <p>Every case here is one that fails silently in production. A token signed with the
 * wrong claims is refused by Apple rather than by us, and a token minted once at startup
 * works for five months and then stops on a day nothing shipped. Neither throws anywhere
 * near the mistake, so the clock is driven by hand rather than waited on.
 */
class AppleMusicTokenTest {

    private static final String TEAM_ID = "8U8T43N65J";
    private static final String KEY_ID = "A8HC8PPLY6";

    /** Apple refuses anything longer, so this is the line the lifetime must stay under. */
    private static final Duration APPLE_CEILING = Duration.ofDays(180);

    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");

    private final KeyPair keyPair = generateKeyPair();
    private final MutableClock clock = new MutableClock(START);

    @Test
    void signsATokenAppleWillAccept() {
        String token = tokenWithKey().value().orElseThrow();

        Jws<Claims> parsed =
                Jwts.parser().verifyWith(keyPair.getPublic()).build().parseSignedClaims(token);

        // The team id, not the media id: Apple identifies the issuer by team.
        assertThat(parsed.getPayload().getIssuer()).isEqualTo(TEAM_ID);
        // Without kid Apple cannot tell which public key to verify against, and refuses.
        assertThat(parsed.getHeader().getKeyId()).isEqualTo(KEY_ID);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
    }

    @Test
    void staysInsideApplesExpiryCeiling() {
        Claims claims = Jwts.parser().verifyWith(keyPair.getPublic()).build()
                .parseSignedClaims(tokenWithKey().value().orElseThrow()).getPayload();

        Duration life = Duration.between(
                claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant());

        assertThat(life).isLessThanOrEqualTo(APPLE_CEILING);
    }

    @Test
    void reusesTheTokenItAlreadyHas() {
        // Signing per request would be waste rather than danger, but a token that changes
        // on every call is the first sign the caching has quietly stopped working.
        AppleMusicToken token = tokenWithKey();

        assertThat(token.value()).isEqualTo(token.value());
    }

    @Test
    void keepsTheSameTokenUntilTheMarginIsReached() {
        AppleMusicToken token = tokenWithKey();
        String first = token.value().orElseThrow();

        clock.advance(Duration.ofDays(100));

        assertThat(token.value()).contains(first);
    }

    @Test
    void mintsAgainBeforeTheTokenCanExpire() {
        // The case nothing else covers: the process outliving the token. Five months on,
        // the old string still parses here and Apple still refuses it.
        AppleMusicToken token = tokenWithKey();
        String first = token.value().orElseThrow();

        clock.advance(Duration.ofDays(145));
        String second = token.value().orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(expiryOf(second)).isAfter(expiryOf(first));
    }

    @Test
    void readsAKeyWhateverTheSecretStoreDidToItsNewlines() {
        // A sealed secret keeps the armour and the line breaks; an env var flattens both.
        assertThat(tokenWith(armoured(pem())).value()).isPresent();
        assertThat(tokenWith(pem()).value()).isPresent();
    }

    @Test
    void hasNoTokenWhenTheDeploymentCarriesNoKey() {
        // Local development, and the reason this is an Optional rather than a throw.
        assertThat(tokenWith("").value()).isEmpty();
    }

    @Test
    void refusesAMalformedKeyAtStartupRatherThanOnTheFirstSearch() {
        assertThatThrownBy(() -> tokenWith("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    private AppleMusicToken tokenWithKey() {
        return tokenWith(pem());
    }

    private AppleMusicToken tokenWith(String privateKey) {
        return new AppleMusicToken(
                new AppleMusicProperties(
                        "https://api.music.apple.com", TEAM_ID, KEY_ID, privateKey, "de"),
                clock);
    }

    private Instant expiryOf(String token) {
        return Jwts.parser().verifyWith(keyPair.getPublic()).build()
                .parseSignedClaims(token).getPayload().getExpiration().toInstant();
    }

    private String pem() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    private static String armoured(String body) {
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /** A real P-256 key per test run, so no private key is ever checked in. */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No P-256 on this JVM", e);
        }
    }

    /** Time the test drives, because the behaviour under test is five months wide. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
