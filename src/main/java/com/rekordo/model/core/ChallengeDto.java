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
 */
public record ChallengeDto(String siteKey) {}
