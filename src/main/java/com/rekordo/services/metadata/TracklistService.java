package com.rekordo.services.metadata;

import com.rekordo.client.musicbrainz.MusicBrainzClient;
import com.rekordo.client.musicbrainz.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.model.core.ExternalRef;
import com.rekordo.model.core.ReleaseSource;
import com.rekordo.model.core.TrackMediumDto;
import com.rekordo.model.core.TracklistDto;
import com.rekordo.model.core.TracklistUnavailableReason;
import com.rekordo.repository.ReleaseRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The titles behind a release's track count (design 26).
 *
 * <p>Answered from the mirror, and from the catalogue exactly once per release. Everything
 * about the section on the sheet follows from that one upstream call being expensive:
 * MusicBrainz is paced at one request per second for the whole process, so a sheet that
 * re-asked on every open would queue behind every search in the app.
 *
 * <p>An absent tracklist is a normal answer here, not an error. Two of them are permanent —
 * a Discogs pressing, which the app can count but never read titles from, and an id no
 * catalogue holds — and both come back as a {@link TracklistUnavailableReason} inside a 200
 * so the client can draw the labelled box 26e asks for. The catalogue failing to answer is
 * the opposite: it is the one state worth retrying, so it stays a 502.
 */
@Service
@RequiredArgsConstructor
public class TracklistService {

    private static final Logger log = LoggerFactory.getLogger(TracklistService.class);

    private final MusicBrainzClient musicBrainzClient;
    private final ReleaseRepository releaseRepository;
    private final MetadataService metadataService;
    private final TrackMirror trackMirror;

    @Transactional
    public TracklistDto tracklist(String releaseId) {
        ExternalRef ref = ExternalRef.parse(releaseId);
        Optional<ReleaseEntity> mirrored = releaseRepository.findByExternalId(ref.toString());

        if (ref.source() == ReleaseSource.DISCOGS) {
            // Discogs search hands over a pressing but no track titles, and there is no
            // lookup by id to go back for them. Permanent, and the count the mirror already
            // holds is still worth stating.
            return unavailable(releaseId, mirrored, TracklistUnavailableReason.DISCOGS);
        }
        if (!isCatalogued(releaseId) || !isMbid(ref.id())) {
            // A hand-entered `local:` copy, or an id from a client this build does not know.
            // Checked against the raw prefix and not the parsed one: ExternalRef treats an
            // unrecognised source as MusicBrainz, which makes `local:<uuid>` look exactly
            // like an mbid and sends it upstream to be told "Invalid mbid." at the cost of
            // a paced request.
            return unavailable(releaseId, mirrored, TracklistUnavailableReason.NOT_IN_CATALOGUE);
        }

        Optional<ReleaseEntity> resolved = mirrored.or(() -> metadataService.mirrorRow(ref));
        if (resolved.isEmpty()) {
            return unavailable(releaseId, Optional.empty(), TracklistUnavailableReason.NOT_IN_CATALOGUE);
        }

        ReleaseEntity entity = resolved.get();
        if (entity.getTracksFetchedAt() == null) {
            fetchTracks(entity, ref);
        }

        List<TrackMediumDto> media = trackMirror.read(entity, entity.getArtistName());
        if (media.isEmpty()) {
            // Asked and answered: the catalogue holds this release and lists no tracks for
            // it. `tracks_fetched_at` is set either way, so this costs one lookup ever.
            return unavailable(releaseId, resolved, TracklistUnavailableReason.NOT_IN_CATALOGUE);
        }
        // The rows are the better count once they exist: the release row's own number came
        // from whichever payload first persisted it, and a search's total has been known to
        // disagree with what the lookup actually lists.
        int counted = media.stream().mapToInt(medium -> medium.tracks().size()).sum();
        return new TracklistDto(releaseId, counted, entity.getDiscCount(), media, null);
    }

    /**
     * The one upstream call, for a release the mirror holds without its titles.
     *
     * <p>Every release persisted from a <em>search</em> lands here: a search result carries
     * media with a count and no tracks at all, so the first sheet that opens it pays for the
     * lookup and no sheet after that does.
     */
    private void fetchTracks(ReleaseEntity entity, ExternalRef ref) {
        Optional<MusicBrainzResponses.Release> release =
                musicBrainzClient.lookupRelease(ref.id());
        if (release.isPresent() && TrackMirror.carriesTracks(release.get())) {
            trackMirror.store(entity, release.get());
            return;
        }
        // Nothing came back, or nothing with tracks on it. Marked anyway — an unmarked
        // release re-queries on every open, which is the trap `has_cover_art` exists to
        // avoid for covers.
        log.debug("MusicBrainz has no tracklist for {}", entity.getExternalId());
        entity.setTracksFetchedAt(Instant.now());
        releaseRepository.save(entity);
    }

    private TracklistDto unavailable(
            String releaseId, Optional<ReleaseEntity> entity, TracklistUnavailableReason reason) {
        return new TracklistDto(
                releaseId,
                entity.map(ReleaseEntity::getTrackCount).orElse(null),
                entity.map(ReleaseEntity::getDiscCount).orElse(null),
                List.of(),
                reason);
    }

    /**
     * Whether the id names a catalogue this app reads, rather than the copy's own store.
     *
     * <p>An unprefixed id passes: every id written before there were two catalogues came
     * from MusicBrainz, and {@link ExternalRef} still reads them that way. A prefix that
     * exists but names nothing — {@code local:} — does not.
     */
    private static boolean isCatalogued(String releaseId) {
        int separator = releaseId.indexOf(':');
        if (separator < 0) {
            return true;
        }
        String prefix = releaseId.substring(0, separator);
        for (ReleaseSource source : ReleaseSource.values()) {
            if (source.prefix().equalsIgnoreCase(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMbid(String id) {
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
