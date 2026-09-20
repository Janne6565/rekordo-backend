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
        /** Source-qualified: "musicbrainz:<uuid>" or "discogs:<int>". */
        String albumId,
        String title,
        String artistName,
        Integer year,
        String primaryType,
        String coverArtUrl) {}
