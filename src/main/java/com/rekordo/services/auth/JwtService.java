package com.rekordo.services.auth;

import com.rekordo.configuration.JwtProperties;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

/** Mints and validates the app's own tokens. The service is its own identity provider. */
@Service
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";
    private static final String CLAIM_EMAIL = "email";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UserEntity user) {
        // Claim-rich on purpose: the client reads its own identity from the token rather
        // than spending a round trip on /me for every page load.
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_TOKEN_TYPE, TokenType.ACCESS.name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_EMAIL, user.getEmail())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(properties.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public String issueRefreshToken(UserEntity user, boolean remember) {
        // Minimal: a long-lived token should carry no more than it needs to be exchanged.
        Duration ttl = remember ? properties.refreshTokenTtl() : properties.sessionRefreshTokenTtl();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_TOKEN_TYPE, TokenType.REFRESH.name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Empty for anything that fails to parse, is expired, or is of the wrong type. */
    public Optional<ParsedToken> parse(String token, TokenType expected) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (!expected.name().equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return Optional.empty();
            }
            Integer tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
            return Optional.of(new ParsedToken(
                    UUID.fromString(claims.getSubject()),
                    tokenVersion == null ? -1 : tokenVersion,
                    claims.get(CLAIM_EMAIL, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record ParsedToken(UUID userId, int tokenVersion, String email) {}
}
