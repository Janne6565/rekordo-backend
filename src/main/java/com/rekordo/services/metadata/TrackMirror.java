package com.rekordo.services.metadata;

import com.rekordo.client.musicbrainz.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.entity.ReleaseTrackEntity;
import com.rekordo.model.core.TrackDto;
import com.rekordo.model.core.TrackMediumDto;
import com.rekordo.repository.ReleaseRepository;
import com.rekordo.repository.ReleaseTrackRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes and reads the tracklist half of the release mirror.
 *
 * <p>Its own component rather than more of {@link MetadataService} because two callers need
 * it from opposite directions: the release lookup stores a tracklist it happened to receive,
 * and {@link TracklistService} asks for one on purpose. Both must agree on when a release
 * counts as "asked", which is the only fact that stops a tracklist-less release from
 * re-querying a one-request-per-second upstream on every open.
 */
@Component
@RequiredArgsConstructor
public class TrackMirror {

    private static final Logger log = LoggerFactory.getLogger(TrackMirror.class);

    private final ReleaseTrackRepository trackRepository;
    private final ReleaseRepository releaseRepository;

    /**
     * Whether this payload carries a tracklist at all.
     *
     * <p>A search result and a lookup deserialise into the same {@code Release}, but only
     * the lookup asks for {@code recordings}. Storing a search's empty media as "this
     * release has no tracks" would be a lie the mirror never revisits, so the two are told
     * apart here rather than trusted to the caller.
     */
    public static boolean carriesTracks(MusicBrainzResponses.Release release) {
        return release.media() != null
                && release.media().stream().anyMatch(medium -> medium.tracks() != null);
    }

    /**
     * Replaces this release's stored tracklist with the payload's, and marks it fetched.
     *
     * <p>Marked even when the payload turns out to hold no tracks: "the catalogue was asked
     * and had nothing" is the answer that must survive, otherwise the sheet re-asks forever.
     */
    public void store(ReleaseEntity entity, MusicBrainzResponses.Release release) {
        List<ReleaseTrackEntity> rows = new ArrayList<>();
        List<MusicBrainzResponses.Medium> media = release.media() == null ? List.of() : release.media();
        for (int index = 0; index < media.size(); index++) {
            MusicBrainzResponses.Medium medium = media.get(index);
            if (medium.tracks() == null) {
                continue;
            }
            // `position` is absent often enough that the array order has to stand in for it,
            // and the array is the catalogue's own order.
            int mediumPosition = medium.position() == null ? index + 1 : medium.position();
            for (int trackIndex = 0; trackIndex < medium.tracks().size(); trackIndex++) {
                MusicBrainzResponses.Track track = medium.tracks().get(trackIndex);
                if (track.title() == null || track.title().isBlank()) {
                    continue;
                }
                ReleaseTrackEntity row = new ReleaseTrackEntity();
                row.setId(UUID.randomUUID());
                row.setReleaseId(entity.getId());
                row.setMediumPosition(mediumPosition);
                row.setMediumFormat(medium.format());
                row.setMediumTitle(blankToNull(medium.title()));
                row.setPosition(track.position() == null ? trackIndex + 1 : track.position());
                // A missing number is the one thing worth deriving, and only as a last
                // resort: an unnumbered row would leave the column blank on every line.
                row.setNumber(blankToNull(track.number()) == null
                        ? String.valueOf(track.position() == null ? trackIndex + 1 : track.position())
                        : track.number());
                row.setTitle(track.title());
                row.setLengthMs(track.length());
                row.setArtistName(creditOf(track));
                rows.add(row);
            }
        }

        trackRepository.deleteByReleaseId(entity.getId());
        trackRepository.saveAll(rows);
        entity.setTracksFetchedAt(Instant.now());
        releaseRepository.save(entity);
        log.debug("Stored {} tracks for release {}", rows.size(), entity.getExternalId());
    }

    /**
     * The stored tracklist, grouped into media.
     *
     * @param releaseArtist the release's own credit. A track credited to it carries no
     *                      artist in the response at all — repeating "Pink Floyd" on
     *                      thirteen rows is noise, and the same field on a compilation is
     *                      the only useful thing on the row.
     */
    public List<TrackMediumDto> read(ReleaseEntity entity, String releaseArtist) {
        Map<Integer, List<ReleaseTrackEntity>> byMedium = new LinkedHashMap<>();
        trackRepository
                .findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId())
                .forEach(row -> byMedium
                        .computeIfAbsent(row.getMediumPosition(), key -> new ArrayList<>())
                        .add(row));

        List<TrackMediumDto> media = new ArrayList<>();
        byMedium.forEach((position, rows) -> media.add(new TrackMediumDto(
                position,
                rows.getFirst().getMediumFormat(),
                rows.getFirst().getMediumTitle(),
                rows.stream()
                        .map(row -> new TrackDto(
                                row.getNumber(),
                                row.getTitle(),
                                row.getLengthMs(),
                                Objects.equals(row.getArtistName(), releaseArtist)
                                        ? null
                                        : row.getArtistName()))
                        .toList())));
        return media;
    }

    /** MusicBrainz says "" for "unnamed disc" far more often than it says null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String creditOf(MusicBrainzResponses.Track track) {
        if (track.artistCredit() == null || track.artistCredit().isEmpty()) {
            return null;
        }
        // Joined exactly as MetadataMapper joins the release credit, and it has to be: the
        // reader drops a track's artist when it equals the release's, and a comma here
        // against an ampersand there would put "Pink Floyd" on all twenty-six rows.
        String credit = track.artistCredit().stream()
                .map(MusicBrainzResponses.ArtistCredit::name)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
        return credit.isBlank() ? null : credit;
    }
}
