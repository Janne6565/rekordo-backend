package com.rekordo.client.musicbrainz;

import com.rekordo.client.UpstreamPacer;
import com.rekordo.configuration.MusicBrainzProperties;
import com.rekordo.model.exception.UpstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the MusicBrainz web service.
 *
 * <p>Every outbound call goes through {@link UpstreamPacer}, because MusicBrainz bans
 * clients that exceed one request per second. That pacing is per process — the app runs a
 * single replica, and scaling out would need a shared limiter instead.
 */
@Component
public class MusicBrainzClient {

    private static final Logger log = LoggerFactory.getLogger(MusicBrainzClient.class);
    // `recordings` is what turns `media` from a count into a tracklist, and it brings the
    // per-track artist credits with it because `artist-credits` is already asked for. It
    // costs nothing extra: this is the same single request either way, and the response
    // grows only for releases somebody opened.
    private static final String LOOKUP_INCLUDES = "artist-credits+labels+release-groups+media+recordings";

    /** "https://www.discogs.com/artist/1055923" and "…/artist/1055923-Daughter" both occur. */
    private static final Pattern DISCOGS_ARTIST_URL = Pattern.compile("/artist/(\\d+)");

    private final RestClient restClient;
    private final UpstreamPacer pacer;

    public MusicBrainzClient(RestClient musicBrainzRestClient, MusicBrainzProperties properties) {
        this.restClient = musicBrainzRestClient;
        this.pacer = new UpstreamPacer(properties.requestsPerSecond());
    }

    public List<MusicBrainzResponses.Release> searchReleases(String query, int limit) {
        return search("release", query, limit);
    }

    /**
     * Artists matching a name.
     *
     * The artist index is the only one that can answer "who is this?" — the release index
     * matches on title, so a bare band name there returns records *called* that name and
     * nothing by the band. It also scores usefully: an exact name lands at 100 and
     * substring matches trail well below, which is what lets the caller tell "Daughter"
     * from "Anyone's Daughter".
     */
    public List<MusicBrainzResponses.Artist> searchArtists(String query, int limit) {
        pacer.awaitSlot();
        try {
            MusicBrainzResponses.ArtistSearchResponse response = restClient
                    .get()
                    .uri(uri -> uri.path("/artist")
                            .queryParam("query", query)
                            .queryParam("limit", limit)
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(MusicBrainzResponses.ArtistSearchResponse.class);
            if (response == null || response.artists() == null) {
                return List.of();
            }
            log.debug("MusicBrainz artist search '{}' returned {}", query, response.artists().size());
            return response.artists();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("MusicBrainz", e);
        }
    }

    /** Albums (release groups), with the total the query matched — used for the type counts. */
    public MusicBrainzResponses.ReleaseGroupSearchResponse searchReleaseGroups(String query, int limit) {
        pacer.awaitSlot();
        try {
            MusicBrainzResponses.ReleaseGroupSearchResponse response = restClient
                    .get()
                    .uri(uri -> uri.path("/release-group")
                            .queryParam("query", query)
                            .queryParam("limit", limit)
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(MusicBrainzResponses.ReleaseGroupSearchResponse.class);
            return response == null
                    ? new MusicBrainzResponses.ReleaseGroupSearchResponse(0, List.of())
                    : response;
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("MusicBrainz", e);
        }
    }

    public List<MusicBrainzResponses.Release> findByBarcode(String barcode) {
        // Quoted so a barcode is matched as one term rather than tokenised by Lucene.
        return search("release", "barcode:\"" + barcode + "\"", 25);
    }

    /**
     * Every pressing of one album. Bitches Brew has 47 of them, so the caller pages rather
     * than pretending the list is short.
     */
    public List<MusicBrainzResponses.Release> findReleasesInGroup(String releaseGroupMbid, int limit) {
        return search("release", "rgid:" + releaseGroupMbid, limit);
    }

    private List<MusicBrainzResponses.Release> search(String resource, String query, int limit) {
        pacer.awaitSlot();
        try {
            MusicBrainzResponses.SearchResponse response = restClient
                    .get()
                    .uri(uri -> uri.path("/" + resource)
                            .queryParam("query", query)
                            .queryParam("limit", limit)
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(MusicBrainzResponses.SearchResponse.class);
            if (response == null || response.releases() == null) {
                return List.of();
            }
            log.debug("MusicBrainz search '{}' returned {} releases", query, response.releases().size());
            return response.releases();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("MusicBrainz", e);
        }
    }

    /**
     * Which Discogs artist this MusicBrainz artist is, if MusicBrainz knows.
     *
     * <p>The relation carries a page URL rather than an id — "https://www.discogs.com/artist/1055923",
     * sometimes with a slug after it — so the id is read off the end. Anything that does
     * not end in a number is a URL shape we have not seen, and is skipped rather than
     * guessed at: a wrong id fetches a real artist who happens to be somebody else.
     */
    public Optional<Long> discogsArtistId(UUID mbid) {
        pacer.awaitSlot();
        MusicBrainzResponses.ArtistLookup artist;
        try {
            artist = restClient
                    .get()
                    .uri(uri -> uri.path("/artist/{mbid}")
                            .queryParam("inc", "url-rels")
                            .queryParam("fmt", "json")
                            .build(mbid))
                    .retrieve()
                    .body(MusicBrainzResponses.ArtistLookup.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("MusicBrainz", e);
        }
        if (artist == null || artist.relations() == null) {
            return Optional.empty();
        }
        return artist.relations().stream()
                .filter(relation -> "discogs".equals(relation.type()))
                .map(MusicBrainzResponses.Relation::url)
                .filter(Objects::nonNull)
                .map(MusicBrainzResponses.RelationUrl::resource)
                .filter(Objects::nonNull)
                .map(MusicBrainzClient::trailingId)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** Package-private for the test: this is the step that can quietly resolve to the wrong artist. */
    static Optional<Long> trailingId(String resource) {
        Matcher matcher = DISCOGS_ARTIST_URL.matcher(resource);
        if (!matcher.find()) {
            log.debug("Discogs relation '{}' has no artist id in it", resource);
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<MusicBrainzResponses.Release> lookupRelease(String mbid) {
        pacer.awaitSlot();
        try {
            return Optional.ofNullable(restClient
                    .get()
                    .uri(uri -> uri.path("/release/{mbid}")
                            .queryParam("inc", LOOKUP_INCLUDES)
                            .queryParam("fmt", "json")
                            .build(mbid))
                    .retrieve()
                    .body(MusicBrainzResponses.Release.class));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            // MusicBrainz genuinely has no such release, or will never accept the id as one
            // ("Invalid mbid."). Neither is an upstream failure — reporting either as a 502
            // would tell the client to retry something that cannot change.
            return Optional.empty();
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("MusicBrainz", e);
        }
    }
}
