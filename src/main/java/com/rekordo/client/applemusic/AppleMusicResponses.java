package com.rekordo.client.applemusic;

import java.util.List;

/** The slice of the Apple Music API this app reads. */
public final class AppleMusicResponses {

    private AppleMusicResponses() {}

    public record SearchResponse(Results results) {
    }

    /** One album fetched by id. Apple answers with a list of one rather than the album. */
    public record AlbumsResponse(List<Album> data) {
    }

    public record Results(AlbumData albums) {
    }

    public record AlbumData(List<Album> data) {
    }

    public record Album(String id, Attributes attributes) {
    }

    public record Attributes(
            String name,
            String artistName,
            Artwork artwork,
            /** Optional, and partial when present: "1970" and "1970-03-30" both occur. */
            String releaseDate,
            String recordLabel,
            String upc,
            Integer trackCount,
            /** The Apple Music web page for this album. Always present. */
            String url
    ) {
    }

    public record Artwork(String url, Integer width, Integer height, String bgColor, String textColor1) {
    }

    /**
     * One album fetched by id, read for its tracks this time.
     *
     * <p>The same response {@link AlbumsResponse} reads: Apple includes the album's
     * {@code tracks} relationship by default, so nothing extra is asked for.
     */
    public record AlbumWithTracksResponse(List<AlbumWithTracks> data) {
    }

    public record AlbumWithTracks(String id, Attributes attributes, Relationships relationships) {
    }

    public record Relationships(TrackData tracks) {
    }

    /** {@code next} is set past 300 tracks; no record anybody shelves gets there. */
    public record TrackData(List<Track> data, String next) {
    }

    /** A song, or a music video on a deluxe edition -- both are on the album's list. */
    public record Track(String id, String type, TrackAttributes attributes) {
    }

    public record TrackAttributes(
            String name,
            Integer trackNumber,
            Integer discNumber,
            Integer durationInMillis,
            String artistName
    ) {
    }
}
