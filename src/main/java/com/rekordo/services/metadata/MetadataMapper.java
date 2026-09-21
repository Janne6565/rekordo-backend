package com.rekordo.services.metadata;

import com.rekordo.client.musicbrainz.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ArtistDto;
import com.rekordo.model.core.CoverThemeDto;
import com.rekordo.model.core.ExternalRef;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.ReleaseDto;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure translation between the MusicBrainz payload, the stored row and the API DTO. */
public final class MetadataMapper {

    private MetadataMapper() {}

    public static String artistName(MusicBrainzResponses.Release release) {
        if (release.artistCredit() == null || release.artistCredit().isEmpty()) {
            return "Unknown artist";
        }
        return release.artistCredit().stream()
                .map(MusicBrainzResponses.ArtistCredit::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(", "));
    }

    public static UUID artistMbid(MusicBrainzResponses.Release release) {
        if (release.artistCredit() == null) {
            return null;
        }
        return release.artistCredit().stream()
                .map(MusicBrainzResponses.ArtistCredit::artist)
                .filter(artist -> artist != null && artist.id() != null)
                .findFirst()
                .map(artist -> UUID.fromString(artist.id()))
                .orElse(null);
    }

    /** MusicBrainz dates are partial: "1980", "1980-10" and "1980-10-08" all occur. */
    public static Integer year(String date) {
        if (date == null || date.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Format format(MusicBrainzResponses.Release release) {
        if (release.media() == null || release.media().isEmpty()) {
            return Format.OTHER;
        }
        // A release can span media (a CD+DVD set); the first medium names the edition.
        return Format.fromMediumName(release.media().getFirst().format());
    }

    /** Discs across every medium — a 2xLP is one release with two of them. */
    public static Integer discCount(MusicBrainzResponses.Release release) {
        if (release.media() == null || release.media().isEmpty()) {
            return null;
        }
        int total = release.media().stream()
                .map(MusicBrainzResponses.Medium::discCount)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        // A medium with no disc-count is still a disc; fall back to counting the media.
        return total > 0 ? total : release.media().size();
    }

    /**
     * Tracks across every medium.
     *
     * <p>A <em>search</em> result states this at the top level; a lookup does not, and states
     * it per medium instead. Both paths land in the same column, so the fallback is what
     * keeps a release opened directly — rather than found through a search — from knowing
     * how many tracks it has. The tracklist section states that count before its titles
     * arrive (design 26e), and a null there is a header that fills in late.
     */
    public static Integer trackCount(MusicBrainzResponses.Release release) {
        if (release.trackCount() != null) {
            return release.trackCount();
        }
        if (release.media() == null || release.media().isEmpty()) {
            return null;
        }
        int total = release.media().stream()
                .map(MusicBrainzResponses.Medium::trackCount)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
        return total > 0 ? total : null;
    }

    public static String label(MusicBrainzResponses.Release release) {
        return firstLabelInfo(release)
                .map(MusicBrainzResponses.LabelInfo::label)
                .map(MusicBrainzResponses.Label::name)
                .orElse(null);
    }

    public static String catalogNumber(MusicBrainzResponses.Release release) {
        return firstLabelInfo(release)
                .map(MusicBrainzResponses.LabelInfo::catalogNumber)
                .orElse(null);
    }

    private static Optional<MusicBrainzResponses.LabelInfo> firstLabelInfo(MusicBrainzResponses.Release release) {
        List<MusicBrainzResponses.LabelInfo> infos = release.labelInfo();
        if (infos == null) {
            return Optional.empty();
        }
        return infos.stream().filter(info -> info.label() != null || info.catalogNumber() != null).findFirst();
    }

    public static ArtistDto toArtistDto(MusicBrainzResponses.Artist artist) {
        if (artist == null || artist.id() == null || artist.name() == null) {
            return null;
        }
        MusicBrainzResponses.LifeSpan span = artist.lifeSpan();
        return new ArtistDto(
                UUID.fromString(artist.id()),
                artist.name(),
                // Blank rather than null when absent, so a client never renders "null".
                artist.disambiguation() == null ? "" : artist.disambiguation(),
                artist.type(),
                artist.country(),
                span == null ? null : span.begin(),
                span == null ? null : span.end(),
                artist.score());
    }

    public static AlbumDto toAlbumDto(MusicBrainzResponses.ReleaseGroup group, String coverArtUrl) {
        if (group == null || group.id() == null) {
            return null;
        }
        String artist = group.artistCredit() == null || group.artistCredit().isEmpty()
                ? "Unknown artist"
                : group.artistCredit().stream()
                        .map(MusicBrainzResponses.ArtistCredit::name)
                        .filter(name -> name != null && !name.isBlank())
                        .collect(Collectors.joining(", "));
        return new AlbumDto(
                ExternalRef.musicBrainz(group.id()).toString(),
                group.title() == null ? "Untitled" : group.title(),
                artist,
                year(group.firstReleaseDate()),
                group.primaryType(),
                coverArtUrl,
                // The Cover Art Archive serves one fixed image; there is no template.
                null);
    }

    public static ReleaseDto toDto(ReleaseEntity entity, String albumId) {
        CoverThemeDto theme = entity.getDominantColor() == null || entity.getLightness() == null
                ? null
                : new CoverThemeDto(
                        entity.getDominantColor(),
                        entity.getAccentColor(),
                        entity.getLightness(),
                        entity.getLightness() < CoverPalette.DARK_CHROME_THRESHOLD);
        return new ReleaseDto(
                entity.getExternalId(),
                albumId,
                entity.getTitle(),
                entity.getArtistName(),
                entity.getYear(),
                entity.getFormat(),
                entity.getLabel(),
                entity.getCatalogNumber(),
                entity.getCountry(),
                entity.getBarcode(),
                entity.getReleaseDate(),
                entity.getTrackCount(),
                entity.getDiscCount(),
                coverArtUrl(entity),
                theme);
    }

    /**
     * The cover URL, or null once we know there is nothing behind it.
     *
     * The URL is built from the mbid and exists whether or not the archive holds any bytes,
     * so handing it over unconditionally made every client discover the 404 by failing to
     * render an image. Null is reserved for a definite no; while the answer is still
     * unknown the URL is returned and the client falls back if it does not load.
     */
    private static String coverArtUrl(ReleaseEntity entity) {
        return Boolean.FALSE.equals(entity.getHasCoverArt()) ? null : entity.getCoverArtUrl();
    }
}
