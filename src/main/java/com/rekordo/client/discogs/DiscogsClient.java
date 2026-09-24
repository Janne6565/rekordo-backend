package com.rekordo.client.discogs;

import com.rekordo.client.CoverProbe;
import com.rekordo.client.ImageHosts;
import com.rekordo.client.UpstreamPacer;
import com.rekordo.configuration.DiscogsProperties;
import com.rekordo.model.exception.UpstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * Talks to the Discogs database.
 *
 * <p>Every call is paced. Discogs publishes its quota per minute — 25 anonymous, 60 with a
 * token — and answers 429 rather than queueing, so exceeding it is a failed search, not a
 * slow one.
 */
@Component
public class DiscogsClient {

    private static final Logger log = LoggerFactory.getLogger(DiscogsClient.class);

    /** Discogs' image CDN: {@code i.discogs.com}, and {@code img.discogs.com} on older rows. */
    static final String IMAGE_DOMAIN = "discogs.com";

    private final RestClient restClient;
    private final UpstreamPacer pacer;
    private final DiscogsProperties properties;

    public DiscogsClient(RestClient discogsRestClient, DiscogsProperties properties) {
        this.restClient = discogsRestClient;
        this.properties = properties;
        this.pacer = UpstreamPacer.perMinute(properties.requestsPerMinute());
        if (!properties.authenticated()) {
            log.warn(
                    "Discogs has no token: the quota is 25 requests a minute instead of 60, "
                            + "and every cover image comes back empty.");
        }
    }

    /** Free-text search, the way the add flow's box works. */
    public List<DiscogsResponses.SearchResult> search(String query, int limit) {
        return get(uri -> uri.path("/database/search")
                .queryParam("q", query)
                .queryParam("type", "release")
                .queryParam("per_page", limit)
                .build());
    }

    /**
     * Every pressing of one record, found by artist and title rather than by master id.
     *
     * <p>This is what bridges a MusicBrainz album to Discogs' pressings of it: the two
     * databases share no identifiers, but they agree on what a record is called. Verified
     * precise in practice — "Fred again.." plus "Ten Days" returns those four vinyl
     * pressings and nothing else.
     */
    public List<DiscogsResponses.SearchResult> pressingsOf(String artist, String title, int limit) {
        return get(uri -> uri.path("/database/search")
                .queryParam("artist", artist)
                .queryParam("release_title", title)
                .queryParam("type", "release")
                .queryParam("per_page", limit)
                .build());
    }

    /** A barcode identifies one pressing, which is exactly what a scan wants. */
    public List<DiscogsResponses.SearchResult> findByBarcode(String barcode) {
        return get(uri -> uri.path("/database/search")
                .queryParam("barcode", barcode)
                .queryParam("type", "release")
                .queryParam("per_page", 25)
                .build());
    }

    /**
     * The portrait for one artist, by Discogs id.
     *
     * <p>Prefers the image Discogs marks "primary": the secondaries on a well-filled artist
     * are live photographs, sleeve scans and band logos, any of which would read as the
     * wrong kind of thing in a 46px circle. The 150px thumbnail is taken rather than the
     * full image — these avatars are 46 and 62 pixels, and the originals run past half a
     * megabyte.
     */
    public Optional<String> artistImageUrl(long artistId) {
        if (!properties.authenticated()) {
            return Optional.empty();
        }
        pacer.awaitSlot();
        DiscogsResponses.ArtistResponse artist;
        try {
            artist = restClient
                    .get()
                    .uri(uri -> uri.path("/artists/{id}").build(artistId))
                    .retrieve()
                    .body(DiscogsResponses.ArtistResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            // MusicBrainz pointed at an artist Discogs no longer has. That is an answer.
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Discogs", e);
        }
        return preferredImage(artist == null ? null : artist.images());
    }

    /**
     * One album by its master id, for the sleeve.
     *
     * <p>The Discogs counterpart of the Cover Art Archive URL a MusicBrainz album gets for
     * free. A wishlist entry names an album, artwork belongs to a pressing, and the mirror
     * can only answer for albums it happens to hold a pressing of -- so an album that
     * arrived by any route other than searching on this deployment had no sleeve and no way
     * to ever get one. This is that way.
     *
     * <p>Empty rather than a throw for a master Discogs does not have: that is an answer
     * about the album, not a failure of the request.
     */
    public Optional<DiscogsResponses.MasterResponse> master(long masterId) {
        if (!properties.authenticated()) {
            return Optional.empty();
        }
        pacer.awaitSlot();
        try {
            return Optional.ofNullable(restClient
                    .get()
                    .uri(uri -> uri.path("/masters/{id}").build(masterId))
                    .retrieve()
                    .body(DiscogsResponses.MasterResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Discogs", e);
        }
    }

    /** The sleeve to draw for an album, chosen the same way an artist's portrait is. */
    public Optional<String> coverOf(DiscogsResponses.MasterResponse master) {
        return master == null ? Optional.empty() : preferredImage(master.images());
    }

    /**
     * The one picture worth putting in a circle, out of everything Discogs holds.
     *
     * <p>Package-private for the test. Prefers the thumbnail of the primary image, falls
     * back to the first image of any kind, and to the full-size URI when no thumbnail was
     * generated. Blank strings are Discogs' way of saying "not for you" to an anonymous
     * caller, and are treated as absent rather than handed to an {@code <img>}.
     */
    static Optional<String> preferredImage(List<DiscogsResponses.ArtistImage> images) {
        if (images == null || images.isEmpty()) {
            return Optional.empty();
        }
        return images.stream()
                .filter(image -> "primary".equals(image.type()))
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(image -> isUsable(image.uri150()) ? image.uri150() : image.uri())
                .filter(DiscogsClient::isUsable);
    }

    private static boolean isUsable(String url) {
        return url != null && !url.isBlank();
    }

    private List<DiscogsResponses.SearchResult> get(
            java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uri) {
        pacer.awaitSlot();
        try {
            DiscogsResponses.SearchResponse response = restClient
                    .get()
                    .uri(uri::apply)
                    .retrieve()
                    .body(DiscogsResponses.SearchResponse.class);
            if (response == null || response.results() == null) {
                return List.of();
            }
            return response.results();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Discogs", e);
        }
    }

    /**
     * The bytes behind one Discogs image URL, for palette sampling.
     *
     * Not paced: these live on Discogs' image CDN rather than the API host, so they do not
     * spend the per-minute quota the pacer is protecting. A cover the CDN says is not there
     * is an answer — absent — while a CDN that throttles or times out is not an answer about
     * this release at all, and saying so is what keeps a bad minute from taking the picture
     * off a record for good. See {@link CoverProbe}.
     */
    public CoverProbe fetchImage(String url) {
        if (!isUsable(url)) {
            // No address to ask: this pressing has no cover, and no amount of retrying finds one.
            return CoverProbe.absent();
        }
        if (!ImageHosts.isHttpsOn(url, IMAGE_DOMAIN)) {
            // Every request from this client carries the Discogs token, and a row's cover
            // address can come from a client (adoptFromClient). Refused without asking, and
            // reported as unreachable: nothing was learnt, so nothing is written down.
            log.debug("Not fetching an image outside Discogs with the Discogs client: {}", url);
            return CoverProbe.unreachable();
        }
        try {
            byte[] bytes = restClient
                    .get()
                    .uri(java.net.URI.create(url))
                    .retrieve()
                    .body(byte[].class);
            return bytes == null ? CoverProbe.absent() : CoverProbe.found(bytes);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone e) {
            log.debug("Discogs image {} is gone", url);
            return CoverProbe.absent();
        } catch (IllegalArgumentException e) {
            // Not a URL at all, so there is nothing behind it and never will be.
            log.debug("Discogs image url {} is not an address ({})", url, e.getMessage());
            return CoverProbe.absent();
        } catch (RestClientException e) {
            log.debug("Could not reach Discogs image {} ({})", url, e.getMessage());
            return CoverProbe.unreachable();
        }
    }

    /** Whether covers will come back at all, so callers can fall back rather than show gaps. */
    public boolean servesImages() {
        return properties.authenticated();
    }
}
