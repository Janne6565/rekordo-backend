package com.rekordo.client.musicbrainz;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The slice of the MusicBrainz web-service JSON this app actually reads. */
public final class MusicBrainzResponses {

    private MusicBrainzResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(int count, List<Release> releases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistSearchResponse(int count, List<Artist> artists) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseGroupSearchResponse(
            int count, @JsonProperty("release-groups") List<ReleaseGroup> releaseGroups) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(
            String id,
            String title,
            String date,
            String country,
            String barcode,
            @JsonProperty("artist-credit") List<ArtistCredit> artistCredit,
            @JsonProperty("release-group") ReleaseGroup releaseGroup,
            @JsonProperty("label-info") List<LabelInfo> labelInfo,
            List<Medium> media,
            /** Tracks across every medium, which is what the pressing table shows. */
            @JsonProperty("track-count") Integer trackCount,
            /** Present on a lookup, absent from search results — so null means "unknown". */
            @JsonProperty("cover-art-archive") CoverArtArchive coverArtArchive) {}

    /** What the Cover Art Archive holds for a release, as MusicBrainz reports it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoverArtArchive(Boolean front, Boolean artwork, Integer count) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistCredit(String name, Artist artist) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artist(
            String id,
            String name,
            String disambiguation,
            /** "Group" or "Person" — the deck shows it beside the country. */
            String type,
            String country,
            Integer score,
            @JsonProperty("life-span") LifeSpan lifeSpan) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifeSpan(String begin, String end, Boolean ended) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseGroup(
            String id,
            String title,
            @JsonProperty("first-release-date") String firstReleaseDate,
            /** Album, EP, Single, Broadcast, Other — how the artist screen sections itself. */
            @JsonProperty("primary-type") String primaryType,
            @JsonProperty("artist-credit") List<ArtistCredit> artistCredit) {}

    /**
     * An artist lookup asked for its URL relations.
     *
     * <p>The only reason to make this call is {@code relations}: MusicBrainz records where
     * else on the web an artist lives, and one of those places is Discogs. That relation is
     * an editor-verified statement that these two database entries are the same act, which
     * is the difference between showing a portrait and showing a portrait of somebody else
     * with the same name.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistLookup(String id, String name, List<Relation> relations) {}

    /** {@code type} is the relation's kind — "discogs", "official homepage", "wikidata". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(String type, RelationUrl url) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RelationUrl(String resource) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelInfo(@JsonProperty("catalog-number") String catalogNumber, Label label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Label(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Medium(
            String format,
            /** 1-based, and only present on a lookup — search results carry no position. */
            Integer position,
            /** Named discs happen on box sets; everywhere else this is "" rather than null. */
            String title,
            @JsonProperty("disc-count") Integer discCount,
            @JsonProperty("track-count") Integer trackCount,
            /** Only present when the lookup asked for {@code recordings}. */
            List<Track> tracks) {}

    /**
     * One track of a medium.
     *
     * <p>{@code number} is a string and must stay one: CDs number "1", "2", vinyl numbers
     * "A1", "B2", and the second LP of a double starts at "C1". {@code length} is
     * milliseconds and is frequently null on individual tracks of an otherwise complete
     * disc. {@code artistCredit} is per track, which only matters on compilations — it is
     * the release credit repeated on every row of a normal album.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Track(
            String id,
            String number,
            Integer position,
            String title,
            Integer length,
            @JsonProperty("artist-credit") List<ArtistCredit> artistCredit) {}
}
