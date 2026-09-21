package com.rekordo.model.core;

/**
 * Which database a record came from, and its id there — as one opaque string.
 *
 * <p>The app reads two catalogues now. MusicBrainz has the cleaner artist data; Discogs has
 * the physical pressings, which for a record collection is most of the point. They share no
 * identifiers, so a stored id has to say where it came from.
 *
 * <p><strong>One field, not two.</strong> The obvious shape is a source column beside an id
 * column, but a copy's release reference is a field-level mergeable value: two devices
 * editing it would merge {@code source} and {@code id} independently, each under its own
 * clock, and could combine one device's {@code MUSICBRAINZ} with the other's Discogs
 * integer — producing a copy that points at nothing. Encoding both in a single value makes
 * that unrepresentable rather than merely unlikely.
 *
 * <p>Format is {@code source:id}, e.g. {@code musicbrainz:a9e30282-5b37-3f92-b897-b9659a1a312b}
 * or {@code discogs:31679120}.
 */
public record ExternalRef(ReleaseSource source, String id) {

    private static final char SEPARATOR = ':';

    public ExternalRef {
        if (source == null) {
            throw new IllegalArgumentException("An external reference needs a source");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An external reference needs an id");
        }
    }

    public static ExternalRef musicBrainz(String id) {
        return new ExternalRef(ReleaseSource.MUSICBRAINZ, id);
    }

    public static ExternalRef discogs(String id) {
        return new ExternalRef(ReleaseSource.DISCOGS, id);
    }

    public static ExternalRef appleMusic(String id) {
        return new ExternalRef(ReleaseSource.APPLE_MUSIC, id);
    }

    /**
     * Reads a stored reference back.
     *
     * <p>An unprefixed value is treated as MusicBrainz. Every id written before two sources
     * existed came from there, and rejecting them would strand collections that predate
     * this — the migration prefixes what is in the database, but a client that has not
     * synced since can still push an old-style id.
     */
    public static ExternalRef parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Not an external reference: " + value);
        }
        int separator = value.indexOf(SEPARATOR);
        if (separator < 0) {
            return musicBrainz(value);
        }
        ReleaseSource source = ReleaseSource.fromPrefix(value.substring(0, separator));
        return new ExternalRef(source, value.substring(separator + 1));
    }

    /** What gets stored and synced. */
    @Override
    public String toString() {
        return source.prefix() + SEPARATOR + id;
    }
}
