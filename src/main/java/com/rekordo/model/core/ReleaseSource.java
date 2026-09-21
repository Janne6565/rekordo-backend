package com.rekordo.model.core;

/** The catalogues this app reads. */
public enum ReleaseSource {
    MUSICBRAINZ("musicbrainz"),
    DISCOGS("discogs"),
    /**
     * Apple Music, which the search box reads and nothing else does.
     *
     * <p>It has no pressings at all -- one digital album per record, no country variants
     * or label editions -- so an id from here names an album and never a copy of one.
     */
    APPLE_MUSIC("applemusic");

    private final String prefix;

    ReleaseSource(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    /**
     * Unknown prefixes fall back to MusicBrainz rather than throwing.
     *
     * <p>A client one version ahead could push a source this build has never heard of, and
     * refusing the whole sync batch over one unrecognised copy would be a poor trade — the
     * record still round-trips, it just resolves against the wrong catalogue until the
     * server catches up.
     */
    public static ReleaseSource fromPrefix(String prefix) {
        for (ReleaseSource source : values()) {
            if (source.prefix.equalsIgnoreCase(prefix)) {
                return source;
            }
        }
        return MUSICBRAINZ;
    }
}
