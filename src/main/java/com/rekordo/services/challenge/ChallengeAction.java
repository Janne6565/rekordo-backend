package com.rekordo.services.challenge;

/**
 * What a challenge was solved for.
 *
 * <p>The client passes the wire name to the widget as its {@code action}; siteverify echoes
 * it back and {@link TurnstileService} refuses a token whose action is not the one the
 * endpoint expects. Without that check a single token would be interchangeable across all
 * four endpoints -- one solved challenge on the sign-in form would be spendable on the
 * endpoint that sends mail to any address, which is the one worth protecting most.
 */
public enum ChallengeAction {
    REGISTER("register"),
    LOGIN("login"),
    FORGOT_PASSWORD("forgot-password"),
    REQUEST_EMAIL_CONFIRMATION("request-email-confirmation");

    private final String wireName;

    ChallengeAction(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
