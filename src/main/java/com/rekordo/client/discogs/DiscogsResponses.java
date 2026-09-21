package com.rekordo.client.discogs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** The slice of the Discogs API this app reads. */
public final class DiscogsResponses {

    private DiscogsResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(Pagination pagination, List<SearchResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(int items, int pages, int page) {}

    /**
     * One row of a database search.
     *
     * <p>Nearly every field is a maybe. Discogs is community-entered, and it shows: the
     * barcode of one <em>ten days</em> pressing is the runout-groove text rather than a
     * barcode. Nothing here may be trusted to be well-formed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(
            Long id,
            /** The master groups a record's pressings, the way a release group does. */
            @JsonProperty("master_id") Long masterId,
            /** "Artist - Title", undivided. Discogs does not send the two apart. */
            String title,
            Integer year,
            String country,
            String catno,
            List<String> label,
            /** Free text, and frequently not a barcode at all. */
            List<String> barcode,
            List<ReleaseFormat> formats,
            /** Empty unless the request carried a token. */
            String thumb,
            @JsonProperty("cover_image") String coverImage) {}

    /**
     * One artist, fetched by id once MusicBrainz has said which id that is.
     *
     * <p>{@code images} is empty for an anonymous caller — Discogs serves pictures only to
     * a request carrying a token — so an empty list means "no token" as readily as it means
     * "no picture", and the caller checks which before believing it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistResponse(Long id, String name, List<ArtistImage> images) {}

    /**
     * One picture of an artist.
     *
     * <p>{@code type} is "primary" for the one Discogs leads with and "secondary" for the
     * rest; the primary is the portrait, the secondaries are frequently live shots and
     * record-sleeve scans. {@code uri150} is the square thumbnail, which is the only size
     * an avatar has any use for.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ArtistImage(String type, String uri, String uri150) {}

    /**
     * One album, as Discogs calls a master.
     *
     * <p>Only the fields needed to give an album a sleeve and a name. A wish points at a
     * master rather than a pressing, and this is the one way to ask about it by id --
     * everything else Discogs offers is a search.
     */
    public record MasterResponse(Long id, String title, List<ArtistCredit> artists, List<ArtistImage> images) {}

    /** Discogs credits an album to a list; the first entry is the one on the spine. */
    public record ArtistCredit(String name) {}

    /**
     * A format and what kind of one it is.
     *
     * <p>{@code name} is the medium ("Vinyl", "CD", "Cassette", "File"); {@code
     * descriptions} carries what collectors actually care about — "LP", "12\"", "45 RPM",
     * "Limited Edition", "White Label", "Reissue".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReleaseFormat(String name, String qty, List<String> descriptions) {}
}
