package com.rekordo.model.core;


/**
 * A release group — the album, above the individual pressings of it.
 *
 * <p>An artist screen lists these rather than releases because a discography listed by
 * pressing is unreadable: Miles Davis has 51 albums and over 1400 releases, and
 * <em>Bitches Brew</em> alone accounts for 47 of them.
 *
 * <p>{@code primaryType} is how the screen sections itself — Album, EP, Single, Broadcast.
 * Daughter's 330 release groups are mostly sessions, broadcasts and remixes, so a
 * discography that did not separate them would bury the four records anyone is looking for.
 */
public record AlbumDto(
        /** Source-qualified: "musicbrainz:<uuid>", "discogs:<int>" or "applemusic:<int>". */
        String albumId,
        String title,
        String artistName,
        Integer year,
        String primaryType,
        String coverArtUrl,
        /**
         * The artwork as a resizable template, or null from a catalogue that has none.
         *
         * <p>Apple serves one asset at any size through a {@code {w}x{h}} placeholder, so a
         * shelf grid on a phone can ask for 8 KB where a detail sheet asks for 600. The
         * other two catalogues hand over one fixed image, and {@code coverArtUrl} is what
         * every client has always read -- this is additional, never a replacement.
         */
        String coverArtTemplate) {}
