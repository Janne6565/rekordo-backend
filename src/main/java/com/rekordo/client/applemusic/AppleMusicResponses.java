package com.rekordo.client.applemusic;

import java.util.List;

/** The slice of the Apple Music API this app reads. */
public final class AppleMusicResponses {

    private AppleMusicResponses() {}

    public record SearchResponse(Results results) {
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
}
