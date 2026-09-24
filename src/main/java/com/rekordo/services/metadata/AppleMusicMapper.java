package com.rekordo.services.metadata;

import com.rekordo.client.applemusic.AppleMusicResponses;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ExternalRef;
import com.rekordo.model.core.TrackDto;
import com.rekordo.model.core.TrackMediumDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
     * An album's tracks, grouped into discs the way the tracklist sheet draws media.
     *
     * <p>A track credited to the album's own artist carries no artist, exactly as
     * {@link TrackMirror#read} does for MusicBrainz: repeating the name on every row is noise,
     * and on a compilation the same field is the only useful thing on the row. Discs have no
     * format of their own here -- Apple sells no object -- so the medium's format is null.
     */
    public static List<TrackMediumDto> toMedia(AppleMusicResponses.AlbumWithTracks album) {
        if (album == null || album.relationships() == null || album.relationships().tracks() == null
                || album.relationships().tracks().data() == null) {
            return List.of();
        }
        String albumArtist = album.attributes() == null ? null : album.attributes().artistName();
        List<AppleMusicResponses.Track> tracks = album.relationships().tracks().data();

        Map<Integer, List<TrackDto>> byDisc = new TreeMap<>();
        for (int index = 0; index < tracks.size(); index++) {
            AppleMusicResponses.Track track = tracks.get(index);
            AppleMusicResponses.TrackAttributes attributes = track == null ? null : track.attributes();
            if (attributes == null || attributes.name() == null || attributes.name().isBlank()) {
                continue;
            }
            int disc = attributes.discNumber() == null ? 1 : attributes.discNumber();
            // Apple's list is already in album order, so the index stands in for a missing number.
            int number = attributes.trackNumber() == null ? index + 1 : attributes.trackNumber();
            byDisc.computeIfAbsent(disc, key -> new ArrayList<>()).add(new TrackDto(
                    String.valueOf(number),
                    attributes.name(),
                    attributes.durationInMillis(),
                    Objects.equals(attributes.artistName(), albumArtist) ? null : attributes.artistName()));
        }

        List<TrackMediumDto> media = new ArrayList<>();
        byDisc.forEach((disc, rows) -> media.add(new TrackMediumDto(disc, null, null, List.copyOf(rows))));
        return media;
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
