package com.rekordo.services.metadata;

import com.rekordo.client.MusicBrainzClient;
import com.rekordo.client.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.entity.ReleaseTrackEntity;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.TracklistDto;
import com.rekordo.model.core.TracklistUnavailableReason;
import com.rekordo.repository.ReleaseRepository;
import com.rekordo.repository.ReleaseTrackRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tracklist behind a copy (design 26).
 *
 * <p>Two rules carry the whole feature. The catalogue is asked once per release and never
 * again — one paced request per second is shared by every screen in the app — and an absent
 * tracklist is an answer rather than an error, because 26e draws it as a labelled box
 * instead of hiding the section.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TracklistServiceTest {

    private static final String MBID = "e32a3f0b-1c19-3170-bb1c-650893774744";
    private static final String RELEASE_ID = "musicbrainz:" + MBID;

    @Mock private MusicBrainzClient musicBrainzClient;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private MetadataService metadataService;
    @Mock private ReleaseTrackRepository trackRepository;

    private TrackMirror trackMirror;
    private TracklistService service;

    private TracklistService service() {
        if (service == null) {
            trackMirror = new TrackMirror(trackRepository, releaseRepository);
            service = new TracklistService(musicBrainzClient, releaseRepository, metadataService, trackMirror);
        }
        return service;
    }

    private static ReleaseEntity release() {
        ReleaseEntity entity = new ReleaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId(RELEASE_ID);
        entity.setReleaseGroupId(UUID.randomUUID());
        entity.setTitle("Kind of Blue");
        entity.setArtistName("Miles Davis");
        entity.setFormat(Format.VINYL);
        entity.setTrackCount(5);
        entity.setDiscCount(1);
        entity.setFetchedAt(Instant.now());
        return entity;
    }

    private static ReleaseTrackEntity row(UUID releaseId, int medium, int position, String number,
                                          String title, Integer lengthMs, String artist) {
        ReleaseTrackEntity track = new ReleaseTrackEntity();
        track.setId(UUID.randomUUID());
        track.setReleaseId(releaseId);
        track.setMediumPosition(medium);
        track.setMediumFormat("12\" Vinyl");
        track.setPosition(position);
        track.setNumber(number);
        track.setTitle(title);
        track.setLengthMs(lengthMs);
        track.setArtistName(artist);
        return track;
    }

    @Test
    void a_discogs_pressing_is_a_dead_end_that_still_reports_its_count() {
        ReleaseEntity entity = release();
        entity.setExternalId("discogs:31679120");
        when(releaseRepository.findByExternalId("discogs:31679120")).thenReturn(Optional.of(entity));

        TracklistDto tracklist = service().tracklist("discogs:31679120");

        assertThat(tracklist.unavailableReason()).isEqualTo(TracklistUnavailableReason.DISCOGS);
        // The count is the part that is known, and 26e keeps stating it.
        assertThat(tracklist.trackCount()).isEqualTo(5);
        verify(musicBrainzClient, never()).lookupRelease(any());
    }

    @Test
    void a_hand_entered_copy_never_reaches_the_catalogue() {
        String local = "local:" + UUID.randomUUID();

        TracklistDto tracklist = service().tracklist(local);

        assertThat(tracklist.unavailableReason()).isEqualTo(TracklistUnavailableReason.NOT_IN_CATALOGUE);
        // The point of the guard: a `local:` id parses as MusicBrainz, so without it this
        // spends a paced request to be told the id is not an address.
        verify(musicBrainzClient, never()).lookupRelease(any());
    }

    @Test
    void an_unprefixed_legacy_id_is_still_a_musicbrainz_one() {
        ReleaseEntity entity = release();
        entity.setTracksFetchedAt(Instant.now());
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of(row(entity.getId(), 1, 1, "A1", "So What", null, null)));

        // Every id written before there were two catalogues came from MusicBrainz, and the
        // "does this name a catalogue at all" guard must not strand them.
        TracklistDto tracklist = service().tracklist(MBID);

        assertThat(tracklist.unavailableReason()).isNull();
    }

    @Test
    void a_release_the_mirror_holds_without_titles_is_looked_up_once() {
        ReleaseEntity entity = release();
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(musicBrainzClient.lookupRelease(MBID)).thenReturn(Optional.of(new MusicBrainzResponses.Release(
                MBID, "Kind of Blue", "1959", "US", null, null, null, null,
                List.of(new MusicBrainzResponses.Medium("12\" Vinyl", 1, "", null, 2, List.of(
                        new MusicBrainzResponses.Track("t1", "A1", 1, "So What", 545426, null),
                        new MusicBrainzResponses.Track("t2", "A2", 2, "Freddie Freeloader", null, null)))),
                5, null)));
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of(
                        row(entity.getId(), 1, 1, "A1", "So What", 545426, null),
                        row(entity.getId(), 1, 2, "A2", "Freddie Freeloader", null, null)));

        TracklistDto tracklist = service().tracklist(RELEASE_ID);

        assertThat(tracklist.unavailableReason()).isNull();
        assertThat(tracklist.media()).hasSize(1);
        assertThat(tracklist.media().getFirst().tracks())
                .extracting(track -> track.number() + " " + track.title())
                .containsExactly("A1 So What", "A2 Freddie Freeloader");
        // A duration nobody knows stays null all the way to the client, which draws an
        // empty cell rather than a dash.
        assertThat(tracklist.media().getFirst().tracks().get(1).lengthMs()).isNull();
        // Marked, so the next sheet that opens this release costs nothing upstream.
        assertThat(entity.getTracksFetchedAt()).isNotNull();
    }

    @Test
    void a_release_already_fetched_is_never_looked_up_again() {
        ReleaseEntity entity = release();
        entity.setTracksFetchedAt(Instant.now());
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of(row(entity.getId(), 1, 1, "A1", "So What", 545426, null)));

        service().tracklist(RELEASE_ID);

        verify(musicBrainzClient, never()).lookupRelease(any());
    }

    @Test
    void a_catalogue_with_no_tracklist_is_marked_so_it_is_asked_only_once() {
        ReleaseEntity entity = release();
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(musicBrainzClient.lookupRelease(MBID)).thenReturn(Optional.empty());
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of());

        TracklistDto tracklist = service().tracklist(RELEASE_ID);

        assertThat(tracklist.unavailableReason()).isEqualTo(TracklistUnavailableReason.NOT_IN_CATALOGUE);
        assertThat(entity.getTracksFetchedAt()).isNotNull();
    }

    @Test
    void a_track_credited_to_the_release_artist_carries_no_credit_of_its_own() {
        ReleaseEntity entity = release();
        entity.setTracksFetchedAt(Instant.now());
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of(
                        row(entity.getId(), 1, 1, "A1", "So What", null, "Miles Davis"),
                        row(entity.getId(), 1, 2, "A2", "Guest Spot", null, "Bill Evans")));

        TracklistDto tracklist = service().tracklist(RELEASE_ID);

        // Thirteen repetitions of the artist above them is noise; the one that differs is
        // the only useful thing on its row.
        assertThat(tracklist.media().getFirst().tracks().getFirst().artistName()).isNull();
        assertThat(tracklist.media().getFirst().tracks().get(1).artistName()).isEqualTo("Bill Evans");
    }

    @Test
    void media_are_grouped_and_the_catalogue_numbering_survives_verbatim() {
        ReleaseEntity entity = release();
        entity.setTracksFetchedAt(Instant.now());
        entity.setDiscCount(2);
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(trackRepository.findByReleaseIdOrderByMediumPositionAscPositionAsc(entity.getId()))
                .thenReturn(List.of(
                        row(entity.getId(), 1, 1, "A1", "In the Flesh?", 199560, null),
                        row(entity.getId(), 1, 2, "B6", "Goodbye Cruel World", null, null),
                        row(entity.getId(), 2, 1, "C1", "Hey You", 284000, null)));

        TracklistDto tracklist = service().tracklist(RELEASE_ID);

        assertThat(tracklist.media()).hasSize(2);
        // The side break between B6 and C1 is the one thing a vinyl owner looks for here,
        // so nothing may renumber it from the position.
        assertThat(tracklist.media().get(1).tracks().getFirst().number()).isEqualTo("C1");
        assertThat(tracklist.media().get(1).position()).isEqualTo(2);
    }

    @Test
    void an_unnamed_disc_comes_back_as_null_rather_than_the_empty_string() {
        ReleaseEntity entity = release();
        when(releaseRepository.findByExternalId(RELEASE_ID)).thenReturn(Optional.of(entity));
        when(musicBrainzClient.lookupRelease(MBID)).thenReturn(Optional.of(new MusicBrainzResponses.Release(
                MBID, "Kind of Blue", "1959", "US", null, null, null, null,
                List.of(new MusicBrainzResponses.Medium("CD", 1, "", null, 1, List.of(
                        new MusicBrainzResponses.Track("t1", "1", 1, "So What", 545426, null)))),
                1, null)));

        service().tracklist(RELEASE_ID);

        // MusicBrainz says "" for "unnamed" far more often than null, and a client should
        // not have to know that to decide whether to draw a disc name.
        verify(trackRepository).saveAll(org.mockito.ArgumentMatchers.argThat(rows -> {
            ReleaseTrackEntity stored = rows.iterator().next();
            return stored.getMediumTitle() == null;
        }));
    }
}
