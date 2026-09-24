package com.rekordo.client.applemusic;

import com.rekordo.configuration.AppleMusicProperties;
import com.rekordo.configuration.CacheConfig;
import com.rekordo.model.exception.UpstreamUnavailableException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * Talks to the Apple Music catalogue, which is where the search box looks.
 *
 * <p>Apple has no concept of a pressing — its catalogue is one digital album per record,
 * with no country variants, catalogue numbers or label editions. That is exactly why it is
 * used here and nowhere else: a search for a record people own should return the record,
 * not the ten Discogs pressings of it. Everything downstream of the choice stays on
 * Discogs, which is the only one of the two that knows what a pressing is.
 *
 * <p>Nothing here is paced. {@code UpstreamPacer} exists because MusicBrainz allows one
 * request a second and Discogs sixty a minute; Apple is far more generous, and not
 * queueing behind that limit is most of the reason search moved here.
 */
@Component
public class AppleMusicClient {

    /**
     * Apple's own ceiling for a search page, and lower than this app's endpoint allows.
     *
     * <p>Clamped rather than passed through: asking for more is a 400 from Apple, which
     * would turn a generous {@code limit} on our side into a failed search rather than a
     * shorter one.
     */
    private static final int MAX_PAGE = 25;

    private final RestClient restClient;
    private final AppleMusicProperties properties;

    public AppleMusicClient(RestClient appleMusicRestClient, AppleMusicProperties properties) {
        this.restClient = appleMusicRestClient;
        this.properties = properties;
    }

    /**
     * Albums matching a free-text query, in Apple's own relevance order.
     *
     * <p>Empty when this deployment carries no signing key, rather than a throw: local
     * development runs without one, and the caller can still answer from Discogs. An empty
     * list is also what a genuine no-match looks like, which is the right conflation here —
     * both mean "nothing to offer from Apple", and both are answered the same way.
     */
    public List<AppleMusicResponses.Album> searchAlbums(String query, int limit) {
        if (!properties.configured()) {
            return List.of();
        }
        try {
            return albumsOf(restClient
                    .get()
                    .uri(uri -> uri.path("/v1/catalog/{storefront}/search")
                            .queryParam("term", query)
                            .queryParam("types", "albums")
                            .queryParam("limit", Math.min(limit, MAX_PAGE))
                            .build(properties.storefront()))
                    .retrieve()
                    .body(AppleMusicResponses.SearchResponse.class));
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Apple Music", e);
        }
    }

    /**
     * One album by its Apple id, for its artist and title.
     *
     * The bridge to Discogs is artist and title, because the two catalogues share no
     * identifiers but agree on what a record is called. A mirrored album can be read
     * straight out of the local tables; an Apple one never is, because this search writes
     * nothing down -- so the two fields have to be fetched back when somebody asks which
     * pressings the record has.
     *
     * <p>Empty for an id Apple does not have, which is an answer about the album rather
     * than a failure of the request.
     */
    public Optional<AppleMusicResponses.Album> album(String id) {
        if (!properties.configured()) {
            return Optional.empty();
        }
        try {
            AppleMusicResponses.AlbumsResponse response = restClient
                    .get()
                    .uri(uri -> uri.path("/v1/catalog/{storefront}/albums/{id}")
                            .build(properties.storefront(), id))
                    .retrieve()
                    .body(AppleMusicResponses.AlbumsResponse.class);
            return response == null || response.data() == null || response.data().isEmpty()
                    ? Optional.empty()
                    : Optional.ofNullable(response.data().getFirst());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Apple Music", e);
        }
    }

    /**
     * One album by its Apple id, with its tracklist.
     *
     * <p>Cached, and for longer than a search: a released album's tracks do not change, and
     * this is the whole cost of a tracklist sheet for a record from the Apple search. Nothing
     * is written to the mirror -- an Apple album is not a row this app keeps -- so the cache
     * is the only copy between requests.
     *
     * <p>Empty for an id Apple does not have, or with no signing key; a failure to reach
     * Apple throws and is not cached, so the sheet's retry asks again.
     */
    @Cacheable(cacheNames = CacheConfig.APPLE_ALBUM_TRACKS)
    public Optional<AppleMusicResponses.AlbumWithTracks> albumWithTracks(String id) {
        if (!properties.configured()) {
            return Optional.empty();
        }
        try {
            AppleMusicResponses.AlbumWithTracksResponse response = restClient
                    .get()
                    .uri(uri -> uri.path("/v1/catalog/{storefront}/albums/{id}")
                            .build(properties.storefront(), id))
                    .retrieve()
                    .body(AppleMusicResponses.AlbumWithTracksResponse.class);
            return response == null || response.data() == null || response.data().isEmpty()
                    ? Optional.empty()
                    : Optional.ofNullable(response.data().getFirst());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("Apple Music", e);
        }
    }

    /**
     * Unwraps the search envelope, treating every level as optional.
     *
     * <p>Apple leaves the {@code albums} key out entirely when nothing matched rather than
     * sending an empty array, so a no-match arrives here as a null two levels down. That is
     * an answer about the query, not a failure of it.
     */
    private static List<AppleMusicResponses.Album> albumsOf(
            AppleMusicResponses.SearchResponse response) {
        if (response == null
                || response.results() == null
                || response.results().albums() == null
                || response.results().albums().data() == null) {
            return List.of();
        }
        return response.results().albums().data();
    }
}
