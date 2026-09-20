package com.rekordo.model.core;


/**
 * One specific edition of an album — the unit a user actually owns a copy of.
 *
 * <p>Screen 2a lists one row per release <em>and</em> format, which is why the format and
 * the label/catalog/country triple live here rather than on the release group.
 */
public record ReleaseDto(
        /** Source-qualified: "musicbrainz:<uuid>" or "discogs:<int>". */
        String id,
        /** The album this is a pressing of, source-qualified the same way. */
        String albumId,
        String title,
        String artistName,
        Integer year,
        Format format,
        String label,
        String catalogNumber,
        String country,
        String barcode,
        /** Partial dates are normal here: "1970", "1970-03" and "1970-03-30" all occur. */
        String releaseDate,
        Integer trackCount,
        Integer discCount,
        String coverArtUrl,
        CoverThemeDto coverTheme) {

    /** The "Sire · SRK 6095 · US" line under each search result on screen 2a. */
    public String disambiguation() {
        return String.join(
                " · ",
                java.util.stream.Stream.of(label, catalogNumber, country)
                        .filter(part -> part != null && !part.isBlank())
                        .toList());
    }
}
