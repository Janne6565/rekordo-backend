package com.rekordo.services.auth.oauth;

import com.rekordo.configuration.OAuthProperties;
import com.rekordo.entity.OAuthStateEntity;
import com.rekordo.model.core.AuthProviderDto;
import com.rekordo.model.core.OAuthClient;
import com.rekordo.model.exception.OAuthFailedException;
import com.rekordo.repository.OAuthStateRepository;
import com.rekordo.services.auth.OneTimeToken;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The authorization-code half of external sign-in.
 *
 * Hand-rolled rather than spring-security-oauth2-client, following the house pattern: the
 * provider only authenticates the first step, and the app issues its own tokens afterwards.
 */
@Service
@RequiredArgsConstructor
public class OAuthService {

    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    /** Public because the cookie that binds a flow to a browser must not outlive its state. */
    public static final Duration STATE_LIFETIME = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthProperties properties;
    private final OAuthStateRepository stateRepository;
    private final RestClient restClient = RestClient.builder().build();

    /** Only providers that could actually complete a sign-in. */
    public List<AuthProviderDto> available() {
        return properties.safeProviders().entrySet().stream()
                .filter(entry -> entry.getValue().configured())
                .map(entry -> new AuthProviderDto(
                        entry.getKey(),
                        entry.getValue().displayName() == null ? entry.getKey() : entry.getValue().displayName()))
                .toList();
    }

    public OAuthProperties.Provider require(String providerId) {
        OAuthProperties.Provider provider = properties.safeProviders().get(providerId);
        if (provider == null || !provider.configured()) {
            throw new OAuthFailedException("That sign-in provider is not available.");
        }
        return provider;
    }

    /**
     * Where to send the person, and the secret their browser has to keep.
     *
     * <p>Two values rather than one because the state alone proves nothing about who is
     * finishing the flow. The caller puts the binding in a cookie; only the browser that
     * started this can then complete it.
     *
     * @param requestHost the host this request arrived on; see {@link #redirectUri}
     */
    @Transactional
    public Authorization authorizeUrl(String providerId, OAuthClient client, String requestHost) {
        OAuthProperties.Provider provider = require(providerId);

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String binding = OneTimeToken.issue();

        OAuthStateEntity entity = new OAuthStateEntity();
        entity.setState(state);
        entity.setProvider(providerId);
        // Only the hash, as everywhere else here: a row sitting in a leaked database must
        // not be half of a sign-in waiting to be finished.
        entity.setBindingHash(OneTimeToken.hash(binding));
        // Remembered here because the callback comes from the provider and says nothing
        // about who started the flow, yet the two clients have to be finished differently.
        entity.setClient(client);
        entity.setExpiresAt(Instant.now().plus(STATE_LIFETIME));
        entity.setCreatedAt(Instant.now());
        stateRepository.save(entity);

        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(provider.authorizeUrl())
                .queryParam("client_id", provider.clientId())
                .queryParam("redirect_uri", redirectUri(providerId, requestHost))
                .queryParam("response_type", "code")
                .queryParam("scope", provider.scope() == null ? "openid email profile" : provider.scope())
                .queryParam("state", state);

        // Apple rejects a request for name or e-mail scope that does not ask for form_post,
        // and then answers by POSTing the callback rather than redirecting to it.
        if (provider.responseMode() != null && !provider.responseMode().isBlank()) {
            url = url.queryParam("response_mode", provider.responseMode());
        }

        return new Authorization(
                url.build()
                        // Encoded, or the space in a multi-scope value ("openid email profile")
                        // makes an invalid URI and the redirect throws before it is ever sent.
                        .encode()
                        .toUriString(),
                binding);
    }

    /**
     * Consumes the state exactly once and reports which client began the flow; a replayed
     * callback finds it already used.
     *
     * @param binding the secret from the caller's cookie. It has to match the flow this state
     *     names, or a callback URL an attacker holds could be loaded in somebody else's
     *     browser and would sign that browser into the attacker's account. A row with no
     *     binding at all predates the column and is refused for the same reason.
     */
    @Transactional
    public OAuthClient consumeState(String providerId, String state, String binding) {
        OAuthStateEntity entity = stateRepository
                .findById(state == null ? "" : state)
                .filter(candidate -> candidate.getUsedAt() == null)
                .filter(candidate -> candidate.getExpiresAt().isAfter(Instant.now()))
                .filter(candidate -> candidate.getProvider().equals(providerId))
                .orElseThrow(() -> new OAuthFailedException("That sign-in attempt is no longer valid."));
        if (!bound(entity, binding)) {
            // Consumed anyway. The state is now known to somebody it was not issued to, and
            // leaving it live would let them keep trying it on other browsers.
            entity.setUsedAt(Instant.now());
            stateRepository.save(entity);
            log.warn("Callback for {} presented a state its browser does not hold", providerId);
            throw new OAuthFailedException("That sign-in attempt is no longer valid.");
        }
        entity.setUsedAt(Instant.now());
        stateRepository.save(entity);
        return entity.getClient() == null ? OAuthClient.WEB : entity.getClient();
    }

    private static boolean bound(OAuthStateEntity entity, String binding) {
        if (entity.getBindingHash() == null || binding == null || binding.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                entity.getBindingHash().getBytes(StandardCharsets.UTF_8),
                OneTimeToken.hash(binding).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Which client started this flow, without consuming the state.
     *
     * <p>Only for the failure paths: a person who cancels at the provider should land back
     * in the app they started from, and there is no code to exchange there anyway. An
     * unknown or already-used state means nothing better than a guess is available, and
     * the web app is the safe guess — it can render an error, whereas a bogus deep link
     * would do nothing at all.
     */
    @Transactional(readOnly = true)
    public OAuthClient clientFor(String state) {
        return stateRepository
                .findById(state == null ? "" : state)
                .map(OAuthStateEntity::getClient)
                .orElse(OAuthClient.WEB);
    }

    /** Where the callback reopens a native app once the sign-in is finished. */
    public String mobileRedirectUri() {
        return properties.safeMobileRedirectUri();
    }

    /**
     * Exchanges the code for the provider's view of who just signed in.
     *
     * @param requestHost the host the callback arrived on. The provider refuses the exchange
     *     unless its redirect URI is identical to the one the authorize step sent, and the
     *     callback lands on exactly the host that URI named.
     */
    public ExternalIdentity exchange(String providerId, String code, String requestHost) {
        OAuthProperties.Provider provider = require(providerId);
        String secret = "apple".equals(providerId) ? AppleClientSecret.create(provider) : provider.clientSecret();

        Map<?, ?> token;
        try {
            token = restClient
                    .post()
                    .uri(provider.tokenUrl())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("grant_type=authorization_code&code=%s&redirect_uri=%s&client_id=%s&client_secret=%s"
                            .formatted(
                                    enc(code),
                                    enc(redirectUri(providerId, requestHost)),
                                    enc(provider.clientId()),
                                    enc(secret)))
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            log.warn("Token exchange with {} failed", providerId, e);
            throw new OAuthFailedException("Could not complete sign-in with that provider.");
        }
        if (token == null) {
            throw new OAuthFailedException("Could not complete sign-in with that provider.");
        }

        // The id token carries the subject and e-mail for any OIDC provider, and is the
        // only thing Apple returns — it has no userinfo endpoint worth calling.
        Object idToken = token.get("id_token");
        if (idToken instanceof String jwt) {
            ExternalIdentity fromIdToken = IdTokenClaims.read(jwt);
            if (fromIdToken != null) {
                return fromIdToken;
            }
        }

        // Fall back to userinfo for a provider that returned an opaque access token.
        Object accessToken = token.get("access_token");
        if (provider.userInfoUrl() == null || !(accessToken instanceof String bearer)) {
            throw new OAuthFailedException("That provider did not identify the account.");
        }
        Map<?, ?> info = restClient
                .get()
                .uri(provider.userInfoUrl())
                .header("Authorization", "Bearer " + bearer)
                .retrieve()
                .body(Map.class);
        if (info == null || info.get("sub") == null) {
            throw new OAuthFailedException("That provider did not identify the account.");
        }
        return new ExternalIdentity(
                String.valueOf(info.get("sub")),
                info.get("email") == null ? null : String.valueOf(info.get("email")),
                Boolean.parseBoolean(String.valueOf(info.get("email_verified"))),
                info.get("name") == null ? null : String.valueOf(info.get("name")));
    }

    /**
     * Adds the name Apple sent in the callback form, if the id token had none.
     *
     * <p>The id token always wins: it came from the provider directly, while this value
     * passed through the browser.
     */
    public ExternalIdentity named(ExternalIdentity identity, String appleUserJson) {
        if (identity.displayName() != null && !identity.displayName().isBlank()) {
            return identity;
        }
        String name = AppleUserPayload.displayName(appleUserJson);
        return name == null
                ? identity
                : new ExternalIdentity(
                        identity.subject(), identity.email(), identity.emailVerified(), name);
    }

    /**
     * The callback the provider is told to return to.
     *
     * <p>It follows the host the request arrived on, because the state cookie was set on
     * that host and only comes back if the provider returns there. A phone build still
     * pointed at the app's previous host depends on it. Only a configured origin is ever
     * used, and always as configured: the host header is the caller's to write, and TLS ends
     * at the ingress, so the scheme this server sees is not the one the provider must use.
     *
     * @param requestHost the host the request arrived on, without or with a port; anything
     *     not on the list falls back to {@code publicBaseUrl}
     */
    public String redirectUri(String providerId, String requestHost) {
        return originFor(requestHost) + "/api/v1/auth/oauth/" + providerId + "/callback";
    }

    private String originFor(String requestHost) {
        String fallback = trimSlash(properties.publicBaseUrl());
        if (requestHost == null || requestHost.isBlank()) {
            return fallback;
        }
        String host = stripPort(requestHost.trim());
        return Stream.concat(
                        Stream.of(fallback),
                        properties.safeCallbackOrigins().stream().map(OAuthService::trimSlash))
                .filter(origin -> host.equalsIgnoreCase(hostOf(origin)))
                .findFirst()
                .orElse(fallback);
    }

    private static String hostOf(String origin) {
        try {
            String host = URI.create(origin).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException e) {
            // A malformed entry matches nothing rather than taking every sign-in down with it.
            return "";
        }
    }

    private static String stripPort(String host) {
        // A bracketed IPv6 literal carries colons of its own; only a colon after the bracket
        // starts a port.
        int colon = host.lastIndexOf(':');
        return colon > host.lastIndexOf(']') ? host.substring(0, colon) : host;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * @param url where to send the person
     * @param binding the secret their browser has to present again at the callback
     */
    public record Authorization(String url, String binding) {}

    /**
     * @param subject the provider's stable id — the only field safe to key an account on
     * @param emailVerified whether the provider says it has proved the address. An address it
     *     has not proved must never reach an account that already holds it: the provider is
     *     then only repeating something the person typed, and the person may be anybody.
     */
    public record ExternalIdentity(String subject, String email, boolean emailVerified, String displayName) {}
}
