package com.rekordo.client;

import java.net.URI;
import java.util.Locale;

/**
 * Whether an image URL points at a catalogue's own CDN, before the server fetches it.
 *
 * <p>Cover URLs are not only ever written by this server. {@code adoptFromClient} takes a
 * release row from any signed-in device as it was sent, cover address included, and the
 * palette sampler later fetches that address itself. Without a host check that is a
 * server-side request to wherever a client pointed it -- and for a Discogs row it went out
 * through the Discogs client, which carries the account's API token on every request.
 */
public final class ImageHosts {

    private ImageHosts() {}

    /** True for an https URL on {@code domain} itself or any subdomain of it. */
    public static boolean isHttpsOn(String url, String domain) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
            return false;
        }
        String normalised = host.toLowerCase(Locale.ROOT);
        return normalised.equals(domain) || normalised.endsWith("." + domain);
    }
}
