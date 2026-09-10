package com.rekordo.configuration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Set;

/**
 * Cloudflare Turnstile, the bot check in front of the four open endpoints that either hand
 * out a session or send mail to an address the caller chose.
 *
 * <p>The site key is public by design -- it is rendered into the widget -- so clients ask
 * the server for it rather than being built with it. That matters here: one image is
 * deployed to both staging and production, so a build-time value could only be right for
 * one of them.
 *
 * <p>Both keys blank switches the whole thing off, which is the local default. That is
 * deliberate rather than lax: a laptop is not on the widget's domain list, so a required
 * challenge locally would be a form nobody could submit. It fails loudly instead of
 * quietly if only one of the two is set -- half-configured is a mistake, not a mode.
 */
@Validated
@ConfigurationProperties(prefix = "rekordo.turnstile")
public record TurnstileProperties(
        /** Handed to clients as-is. Public. */
        String siteKey,
        /** Never leaves the server: it is the second half of the siteverify call. */
        String secretKey,
        @NotBlank String verifyUrl,
        /**
         * The hostnames a challenge may have been solved on, checked against the
         * {@code hostname} siteverify reports back.
         *
         * <p>The widget's own domain list already refuses to render anywhere else, but that
         * is a client-side rule and a token is redeemed server-side -- so a token minted for
         * some other site that happens to share this account's widget would otherwise be
         * accepted here. Empty skips the check.
         */
        Set<String> hostnames,
        @NotNull Duration timeout) {

    /**
     * @throws IllegalStateException when exactly one of the two keys is set, which is
     *     always a deployment mistake: it would either serve a widget no token from which
     *     can be verified, or verify tokens no client was ever told to produce.
     */
    public boolean enabled() {
        boolean site = siteKey != null && !siteKey.isBlank();
        boolean secret = secretKey != null && !secretKey.isBlank();
        if (site != secret) {
            throw new IllegalStateException(
                    "rekordo.turnstile needs both site-key and secret-key, or neither; got only "
                            + (site ? "site-key" : "secret-key"));
        }
        return site;
    }
}
