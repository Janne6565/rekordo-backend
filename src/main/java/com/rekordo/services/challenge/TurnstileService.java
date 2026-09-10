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
        }
    }

    /** The site key clients render, or {@code null} when the check is switched off. */
    public String siteKey() {
        return properties.enabled() ? properties.siteKey() : null;
    }

    /**
     * @param token what the widget produced, or {@code null} from a client that did not
     *     render one. Ignored entirely when Turnstile is switched off, so a laptop with no
     *     keys configured is not a form nobody can submit.
     * @throws ChallengeFailedException when the token is missing, expired, already spent, or
     *     was solved for a different endpoint or a different site.
     */
    public void verify(String token, ChallengeAction action) {
        if (!properties.enabled()) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new ChallengeFailedException("This request needs a completed verification check.");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new ChallengeFailedException("The verification check could not be completed.");
        }

        SiteVerifyResponse verdict = siteVerify(token);

        if (!verdict.success()) {
            // The codes name the reason precisely and none of them is the caller's business:
            // "invalid-input-secret" is our misconfiguration and "timeout-or-duplicate" tells
            // somebody probing that their replay was noticed. Logged, not returned.
            log.warn("Turnstile rejected a {} token: {}", action.wireName(), verdict.errorCodes());
            throw new ChallengeFailedException("The verification check could not be completed.");
        }
        if (!action.wireName().equals(verdict.action())) {
            log.warn(
                    "Turnstile token for action {} presented at {}",
                    verdict.action(),
                    action.wireName());
            throw new ChallengeFailedException("The verification check could not be completed.");
        }
        Set<String> approved = properties.hostnames();
        if (approved != null && !approved.isEmpty() && !approved.contains(verdict.hostname())) {
            log.warn("Turnstile token solved on unapproved hostname {}", verdict.hostname());
            throw new ChallengeFailedException("The verification check could not be completed.");
        }
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
                throw new ChallengeFailedException("The verification check could not be completed.");
            }
            return verdict;
        } catch (RestClientException ex) {
            // Cloudflare being unreachable fails the request rather than waving it through.
            // Fail-open here would mean an attacker gets a free pass by making siteverify
            // time out, which is the one condition they can most easily arrange.
            log.error("Could not reach Turnstile siteverify", ex);
            throw new ChallengeFailedException("The verification check could not be completed.");
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
