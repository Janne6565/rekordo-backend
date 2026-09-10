package com.rekordo.model.action;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @NotBlank @Email String email,
        /**
         * The Turnstile token from the widget, absent when the server has no keys configured
         * and the client therefore rendered none.
         *
         * <p>Unconstrained on purpose: whether one is required is a deployment question, not
         * a shape question, so {@code TurnstileService} decides. A {@code @NotBlank} here
         * would make every local sign-in impossible.
         */
        String turnstileToken) {}
