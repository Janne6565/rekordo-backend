package com.rekordo.services.metadata;

import com.rekordo.client.applemusic.AppleMusicResponses;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ExternalRef;

/**
 * Apple's albums, as the search box lists them.
 *
 * <p>One row per record rather than per pressing, which is the whole point of reading this
 * catalogue: Apple has no pressings to multiply a result by. Nothing here is persisted --
 * an Apple album is a search result and never a mirror row, so this maps straight to the
 * DTO and stops.
 */
public final class AppleMusicMapper {

    /**
     * The size baked into {@code coverArtUrl} for clients that cannot resize.
     *
     * <p>Large enough for a detail sheet, and the field exists only so a client written
     * before {@code coverArtTemplate} keeps rendering. Anything that reads the template
     * should ask for the size it actually needs instead.
     */
    private static final int FALLBACK_SIZE = 600;

    private AppleMusicMapper() {}

    /** Null for a row too incomplete to show, which the caller drops. */
    public static AlbumDto toAlbumDto(AppleMusicResponses.Album album) {
        if (album == null || album.id() == null || album.attributes() == null) {
            return null;
        }
        AppleMusicResponses.Attributes attributes = album.attributes();
        String template = attributes.artwork() == null ? null : attributes.artwork().url();
        return new AlbumDto(
                ExternalRef.appleMusic(album.id()).toString(),
                attributes.name() == null ? "Untitled" : attributes.name(),
                attributes.artistName() == null ? "Unknown artist" : attributes.artistName(),
                // Apple's releaseDate is optional and partial -- "1970" and "1970-03-30"
                // both occur -- which is the shape MusicBrainz dates already have.
                MetadataMapper.year(attributes.releaseDate()),
                // Apple says isSingle and isCompilation rather than naming a type, and the
                // search list does not section itself the way an artist's discography does.
                null,
                sized(template, FALLBACK_SIZE),
                template);
    }

    /**
     * Fills in an Apple artwork template at one square size.
     *
     * <p>The placeholders are literal braces in the URL Apple sends. A template that has
     * lost them -- or was never one -- is returned untouched rather than mangled, because
     * a working fixed URL beats a broken resizable one.
     */
    public static String sized(String template, int pixels) {
        if (template == null || template.isBlank()) {
            return null;
        }
        return template.replace("{w}", String.valueOf(pixels)).replace("{h}", String.valueOf(pixels));
    }
}
