package com.rekordo.model.action;

import jakarta.validation.constraints.NotBlank;

/**
 * @param rememberMe when false the refresh token is short-lived and its cookie is a session
 *                   cookie, so closing the browser ends the session. Defaults to true for
 *                   clients that do not send it, which is the behaviour that existed before
 *                   the flag did.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        Boolean rememberMe,
        /**
         * The Turnstile token from the widget, absent when the server has no keys configured
         * and the client therefore rendered none.
         *
         * <p>Unconstrained on purpose: whether one is required is a deployment question, not
         * a shape question, so {@code TurnstileService} decides. A {@code @NotBlank} here
         * would make every local sign-in impossible.
         */
        String turnstileToken) {

    public boolean remember() {
        return rememberMe == null || rememberMe;
    }
}
