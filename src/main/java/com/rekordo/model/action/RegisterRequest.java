package com.rekordo.model.action;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // Length only. Composition rules push people towards predictable substitutions
        // without adding real strength.
        @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters") String password,
        /** Optional: someone who would rather not give a name still gets an account. */
        @Size(max = 120) String displayName,
        /**
         * The two ticks from the sign-up screen, neither of them pre-checked.
         *
         * <p>Required rather than merely recorded: consent that the server would accept
         * without is consent nobody actually gave, and the box would be decoration. Both are
         * {@code @NotNull} as well as {@code @AssertTrue} because Bean Validation treats a
         * null as satisfying {@code @AssertTrue} -- an omitted field would otherwise pass.
         */
        @NotNull @AssertTrue(message = "The terms and the privacy policy have to be accepted")
                Boolean acceptedTerms,
        @NotNull @AssertTrue(message = "An account requires confirming you are 16 or older")
                Boolean confirmedAge,
        /**
         * The Turnstile token from the widget, absent when the server has no keys configured
         * and the client therefore rendered none.
         *
         * <p>Unconstrained on purpose: whether one is required is a deployment question, not
         * a shape question, so {@code TurnstileService} decides. A {@code @NotBlank} here
         * would make every local sign-up impossible.
         */
        String turnstileToken) {}
