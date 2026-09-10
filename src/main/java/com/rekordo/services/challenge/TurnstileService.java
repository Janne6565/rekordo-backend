package com.rekordo.services.challenge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rekordo.configuration.TurnstileProperties;
import com.rekordo.model.exception.ChallengeFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Set;

/**
 * Redeems a Turnstile token with Cloudflare before the endpoint behind it runs.
 *
 * <p>The widget in the browser proves nothing on its own -- a token can be forged, and the
 * endpoints here are reachable with a plain POST regardless of what the form did. This is
 * the half that matters, and it is why every one of the four call sites verifies before it
 * touches an account.
 *
 * <p>Three things are checked, not one. {@code success}, obviously; then the {@code action},
 * so a token cannot be spent on a different endpoint than the one it was solved for; then
 * the {@code hostname}, so a token minted on some other site sharing this account's widget
 * is not accepted here.
 *
 * <p>The visitor's IP is deliberately not sent. It is optional to siteverify, and behind
 * Traefik the address this service sees is only as trustworthy as the forwarded header --
 * sending the wrong one would make Cloudflare reject good tokens, which is a worse failure
 * than not sending it at all.
 *
 * <p>All three checks run whether or not the result is acted on. With
 * {@code rekordo.turnstile.enforce} off this is a dry run: the verdict is logged and the
 * request goes through regardless, which is how the check gets switched on for an app that
 * is already on people's phones without locking out the ones who have not updated.
 */
@Service
public class TurnstileService {

    private static final Logger log = LoggerFactory.getLogger(TurnstileService.class);

    /**
     * Cloudflare's cap. A longer string is not a token, so it is refused without spending a
     * round trip -- and without handing an unbounded body to the form encoder.
     */
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final RestClient client;
    private final TurnstileProperties properties;

    public TurnstileService(RestClient turnstileRestClient, TurnstileProperties properties) {
        this.client = turnstileRestClient;
        this.properties = properties;
        if (!properties.enabled()) {
            log.warn("Turnstile is not configured — sign-up, sign-in and the two mail endpoints "
                    + "are protected by the per-IP rate limiter alone");
        } else if (!properties.enforce()) {
            log.warn("Turnstile is configured but not enforcing — verdicts are logged and nothing "
                    + "is refused. Set rekordo.turnstile.enforce once old clients have gone.");
        }
    }

    /** The site key clients render, or {@code null} when the check is switched off. */
    public String siteKey() {
        return properties.enabled() ? properties.siteKey() : null;
    }

    /**
     * Whether a client should refuse to submit without a solved challenge.
     *
     * <p>It has to be told rather than inferred from the site key, or the observing state
     * would be worse than useless: a widget that failed to load for somebody would block a
     * submit the server was going to accept anyway.
     */
    public boolean enforced() {
        return properties.enabled() && properties.enforce();
    }

    /**
     * @param token what the widget produced, or {@code null} from a client that did not
     *     render one. Ignored entirely when Turnstile is switched off, so a laptop with no
     *     keys configured is not a form nobody can submit.
     * @throws ChallengeFailedException when the token is missing, expired, already spent, or
     *     was solved for a different endpoint or a different site -- but only while
     *     {@code rekordo.turnstile.enforce} is on. With it off the same verdict is reached
     *     and logged, and the request proceeds.
     */
    public void verify(String token, ChallengeAction action) {
        if (!properties.enabled()) {
            return;
        }
        String problem = inspect(token, action);
        if (problem == null) {
            return;
        }
        // Logged either way, and only ever logged. "invalid-input-secret" is our own
        // misconfiguration and "timeout-or-duplicate" would tell somebody probing that their
        // replay was noticed, so none of it goes back to the caller -- but a 403 whose reason
        // appears nowhere is a support request nobody can answer.
        if (properties.enforce()) {
            log.warn("Turnstile refused {}: {}", action.wireName(), problem);
            throw new ChallengeFailedException("The verification check could not be completed.");
        }
        // The line to watch during a rollout. "no token" thinning out towards nothing is what
        // says the old clients are gone and enforcing is safe.
        log.warn("Turnstile would have refused {}: {}", action.wireName(), problem);
    }

    /**
     * Runs all three checks and returns why the token is no good, or {@code null} if it is.
     *
     * <p>A string rather than a thrown exception because the observing state needs the same
     * answer without the consequence -- and because the reason is for the log only. It never
     * reaches the caller.
     */
    private String inspect(String token, ChallengeAction action) {
        if (token == null || token.isBlank()) {
            return "no token";
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            return "token over " + MAX_TOKEN_LENGTH + " characters";
        }

        SiteVerifyResponse verdict;
        try {
            verdict = siteVerify(token);
        } catch (SiteVerifyUnreachable ex) {
            // Fail closed when enforcing. Waving the request through because siteverify timed
            // out would hand an attacker the one condition they can most easily arrange.
            return "siteverify unreachable";
        }

        if (!verdict.success()) {
            return "rejected: " + verdict.errorCodes();
        }
        if (!action.wireName().equals(verdict.action())) {
            return "solved for action " + verdict.action();
        }
        Set<String> approved = properties.hostnames();
        if (approved != null && !approved.isEmpty() && !approved.contains(verdict.hostname())) {
            return "solved on unapproved hostname " + verdict.hostname();
        }
        return null;
    }

    private SiteVerifyResponse siteVerify(String token) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.secretKey());
        form.add("response", token);
        try {
            SiteVerifyResponse verdict = client.post()
                    .uri(properties.verifyUrl())
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
            if (verdict == null) {
                throw new SiteVerifyUnreachable();
            }
            return verdict;
        } catch (RestClientException ex) {
            log.error("Could not reach Turnstile siteverify", ex);
            throw new SiteVerifyUnreachable();
        }
    }

    /** Internal only: it never escapes this class, and it is not the client-facing failure. */
    private static final class SiteVerifyUnreachable extends RuntimeException {
        private SiteVerifyUnreachable() {
            super(null, null, false, false);
        }
    }

    /**
     * Only the four fields that are actually read. Cloudflare sends more ({@code challenge_ts},
     * {@code cdata}, and undocumented additions), and the client is configured to ignore what
     * it does not know so a new field cannot start failing every sign-in.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SiteVerifyResponse(
            boolean success,
            String hostname,
            String action,
            @JsonProperty("error-codes") List<String> errorCodes) {}
}
