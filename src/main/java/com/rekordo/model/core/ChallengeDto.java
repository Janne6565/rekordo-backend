package com.rekordo.model.core;

/**
 * What a client needs to draw the bot check, asked for rather than built in.
 *
 * <p>The site key is public -- it is rendered into the widget's markup -- so there is no
 * reason to hide it and one good reason to serve it: one frontend image is deployed to both
 * staging and production, so a value baked at build time could only be correct for one of
 * them. The phone has the same problem in a worse form, since a shipped binary cannot be
 * reconfigured at all.
 *
 * @param siteKey {@code null} when the check is switched off, which is the local default.
 *     A client that sees null renders no widget and sends no token, and the server does not
 *     ask for one.
 * @param enforced whether the server will actually turn a request away without a solved
 *     challenge. False with a site key present is the rollout state: draw the widget and
 *     send what it gives you, but do not hold the submit button hostage to it. Clients have
 *     to be told this rather than inferring it from the site key -- otherwise a widget that
 *     failed to load would block a submit the server was going to accept anyway, which is
 *     the exact outage the state exists to avoid.
 */
public record ChallengeDto(String siteKey, boolean enforced) {}
