package com.rekordo.model.exception;

import org.springframework.http.HttpStatus;

/**
 * The bot check did not pass.
 *
 * <p>403 rather than 400, and rather than any of the statuses these endpoints already use:
 * a client has to be able to tell "solve the challenge again" apart from "wrong password"
 * (401), "that address is taken" (409) and "you have tried too often" (429), because the
 * only useful response to this one is to reset the widget and let the person retry. It is
 * not a field error either -- there is no input to put a message beside.
 */
public class ChallengeFailedException extends BaseException {

    public ChallengeFailedException(String detail) {
        super(HttpStatus.FORBIDDEN, detail);
    }
}
