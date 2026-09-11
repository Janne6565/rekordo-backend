package com.rekordo.services.challenge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.rekordo.configuration.TurnstileProperties;
import com.rekordo.model.exception.ChallengeFailedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
    private final MeterRegistry meterRegistry;

    public TurnstileService(
            RestClient turnstileRestClient, TurnstileProperties properties, MeterRegistry meterRegistry) {
        this.client = turnstileRestClient;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
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
     * Why a verification came out the way it did, as a bounded set.
     *
     * <p>Bounded because it is a metric tag: the log line carries the specifics -- which error
     * codes, which hostname -- and those are exactly the unbounded values that turn a counter
     * into thousands of dead series.
     */
    enum Outcome {
        ACCEPTED,
        NO_TOKEN,
        OVERSIZED,
        UNREACHABLE,
        REJECTED,
        WRONG_ACTION,
        WRONG_HOSTNAME;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** The bounded outcome for the counter, and the unbounded detail for the log. */
    private record Verdict(Outcome outcome, String detail) {
        static Verdict accepted() {
            return new Verdict(Outcome.ACCEPTED, null);
        }
    }

    /**
     * Every counter, at zero, before anything has been verified.
     *
     * <p>A counter created on first use does not exist while everything is fine, and a series
     * that does not exist cannot be charted or alerted on -- the panel reads "no data", which
     * looks exactly like a panel that is broken. The flat zero is the thing worth seeing.
     *
     * <p>Only when the check is configured. With it off nothing is verified at all, and a wall
     * of zeroes would read as "everything is being accepted" rather than "nothing is running".
     */
    @PostConstruct
    void registerCounters() {
        if (!properties.enabled()) {
            return;
        }
        for (ChallengeAction action : ChallengeAction.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(action, outcome);
            }
        }
    }

    private Counter counter(ChallengeAction action, Outcome outcome) {
        return Counter.builder("rekordo.turnstile.verifications")
                .description("Bot-check verifications, by what the endpoint was and how it came out")
                .tag("action", action.wireName())
                .tag("outcome", outcome.tag())
                // Constant for a deployment, and the difference between "would have been
                // refused" and "was refused" -- which is the whole question during a rollout.
                .tag("enforced", String.valueOf(properties.enforce()))
                .register(meterRegistry);
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
        Verdict verdict = inspect(token, action);
        counter(action, verdict.outcome()).increment();
        if (verdict.outcome() == Outcome.ACCEPTED) {
            return;
        }
        String problem = verdict.detail();
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
     * Runs all three checks and says how it came out.
     *
     * <p>Returned rather than thrown because the observing state needs the same answer without
     * the consequence. The detail is for the log and the counter tag is for the chart; the
     * caller sees neither.
     */
    private Verdict inspect(String token, ChallengeAction action) {
        if (token == null || token.isBlank()) {
            return new Verdict(Outcome.NO_TOKEN, "no token");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            return new Verdict(Outcome.OVERSIZED, "token over " + MAX_TOKEN_LENGTH + " characters");
        }

        SiteVerifyResponse verdict;
        try {
            verdict = siteVerify(token);
        } catch (SiteVerifyUnreachable ex) {
            // Fail closed when enforcing. Waving the request through because siteverify timed
            // out would hand an attacker the one condition they can most easily arrange.
            return new Verdict(Outcome.UNREACHABLE, "siteverify unreachable");
        }

        if (!verdict.success()) {
            return new Verdict(Outcome.REJECTED, "rejected: " + verdict.errorCodes());
        }
        if (!action.wireName().equals(verdict.action())) {
            return new Verdict(Outcome.WRONG_ACTION, "solved for action " + verdict.action());
        }
        Set<String> approved = properties.hostnames();
        if (approved != null && !approved.isEmpty() && !approved.contains(verdict.hostname())) {
            return new Verdict(Outcome.WRONG_HOSTNAME, "solved on unapproved hostname " + verdict.hostname());
        }
        return Verdict.accepted();
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
