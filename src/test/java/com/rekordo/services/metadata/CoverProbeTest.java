package com.rekordo.services.metadata;

import com.rekordo.client.CoverArtClient;
import com.rekordo.client.CoverProbe;
import com.rekordo.client.DiscogsClient;
import com.rekordo.client.MusicBrainzClient;
import com.rekordo.client.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.entity.ReleaseGroupEntity;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.repository.ArtistImageRepository;
import com.rekordo.repository.ReleaseGroupRepository;
import com.rekordo.repository.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a cover probe is allowed to conclude.
 *
 * <p>The palette sampler used to treat every empty answer alike, so an archive that timed
 * out or throttled was written down as an archive that has no picture. That is served to
 * clients as a null cover URL, and they cache it — which is how a record lost its sleeve
 * everywhere at once, for good, during an evening of scanning. Reported from the field.
 *
 * <p>So: a definite no is remembered, and an unanswered question is left open.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoverProbeTest {

    private static final String MBID = "0f2d5a1e-4a1e-4e7a-9c1e-2f0d4b6a8c11";
    private static final String MB_RELEASE = "musicbrainz:" + MBID;
    private static final String COVER_URL = "https://coverartarchive.org/release/" + MBID + "/front-500";
    private static final String GROUP_MBID = "1c4a2f6e-2b3d-4c5e-8a9b-0d1e2f3a4b5c";
    private static final String GROUP_COVER_URL =
            "https://coverartarchive.org/release-group/" + GROUP_MBID + "/front-500";

    @Mock private MusicBrainzClient musicBrainzClient;
    @Mock private DiscogsClient discogsClient;
    @Mock private CoverArtClient coverArtClient;
    @Mock private DominantColorExtractor colorExtractor;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private ReleaseGroupRepository releaseGroupRepository;
    @Mock private ArtistImageRepository artistImageRepository;
    @Mock private TrackMirror trackMirror;

    @InjectMocks private MetadataService service;

    private ReleaseEntity mirrored() {
        ReleaseGroupEntity album = new ReleaseGroupEntity();
        album.setId(UUID.randomUUID());
        album.setExternalId("musicbrainz:" + GROUP_MBID);
        album.setTitle("Bitches Brew");
        album.setArtistName("Miles Davis");
        album.setFetchedAt(Instant.now());

        ReleaseEntity entity = new ReleaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId(MB_RELEASE);
        entity.setReleaseGroupId(album.getId());
        entity.setTitle("Bitches Brew");
        entity.setArtistName("Miles Davis");
        entity.setFormat(Format.VINYL);
        entity.setCoverArtUrl(COVER_URL);
        // Not probed yet: exactly the state a release persisted from a search is in.
        entity.setHasCoverArt(null);
        entity.setFetchedAt(Instant.now());

        when(releaseRepository.findByExternalId(MB_RELEASE)).thenReturn(Optional.of(entity));
        when(releaseGroupRepository.findById(album.getId())).thenReturn(Optional.of(album));
        return entity;
    }

    @Test
    void keepsTheCoverWhenTheArchiveCouldNotBeReached() {
        ReleaseEntity entity = mirrored();
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.unreachable());

        ReleaseDto release = service.getRelease(MB_RELEASE);

        assertThat(release.coverArtUrl()).isEqualTo(COVER_URL);
        // Nothing learned, so nothing written: the next lookup asks again.
        assertThat(entity.getHasCoverArt()).isNull();
        verify(releaseRepository, never()).save(any());
    }

    @Test
    void remembersADefiniteNo() {
        ReleaseEntity entity = mirrored();
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.absent());
        when(coverArtClient.fetchGroupThumbnail(GROUP_MBID)).thenReturn(CoverProbe.absent());

        ReleaseDto release = service.getRelease(MB_RELEASE);

        assertThat(release.coverArtUrl()).isNull();
        assertThat(entity.getHasCoverArt()).isFalse();
        verify(releaseRepository).save(entity);
    }

    /**
     * A pressing the archive has no picture of is shown with its album's, rather than blank.
     * Reported from the field: three Kate Bush pressings blank on a friend's shelf.
     */
    @Test
    void fallsBackToTheAlbumsCoverWhenThePressingHasNone() {
        ReleaseEntity entity = mirrored();
        byte[] bytes = {1, 2, 3};
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.absent());
        when(coverArtClient.fetchGroupThumbnail(GROUP_MBID)).thenReturn(CoverProbe.found(bytes));
        when(coverArtClient.frontCoverUrlForGroup(GROUP_MBID)).thenReturn(GROUP_COVER_URL);
        when(colorExtractor.extract(bytes))
                .thenReturn(Optional.of(new CoverPalette("#101010", "#a2573a", 0.2)));

        ReleaseDto release = service.getRelease(MB_RELEASE);

        assertThat(release.coverArtUrl()).isEqualTo(GROUP_COVER_URL);
        assertThat(entity.getHasCoverArt()).isTrue();
        assertThat(entity.getDominantColor()).isEqualTo("#101010");
    }

    /**
     * MusicBrainz's own "no front cover" is about the pressing, not the album, so a freshly
     * looked-up pressing is offered its album's address rather than written down as coverless.
     */
    @Test
    void offersTheAlbumsAddressWhenMusicBrainzSaysThePressingHasNoFront() {
        ReleaseGroupEntity album = new ReleaseGroupEntity();
        album.setId(UUID.randomUUID());
        album.setExternalId("musicbrainz:" + GROUP_MBID);
        album.setTitle("Hounds of Love");
        album.setArtistName("Kate Bush");
        album.setFetchedAt(Instant.now());

        when(releaseRepository.findByExternalId(MB_RELEASE)).thenReturn(Optional.empty());
        when(musicBrainzClient.lookupRelease(MBID)).thenReturn(Optional.of(new MusicBrainzResponses.Release(
                MBID, "Hounds of Love", "1985", "GB", null, null,
                new MusicBrainzResponses.ReleaseGroup(GROUP_MBID, "Hounds of Love", "1985", "Album", null),
                null, null, null,
                new MusicBrainzResponses.CoverArtArchive(false, false, 0))));
        when(releaseGroupRepository.findByExternalId(album.getExternalId())).thenReturn(Optional.of(album));
        when(releaseGroupRepository.findById(album.getId())).thenReturn(Optional.of(album));
        when(releaseRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(coverArtClient.frontCoverUrlForGroup(GROUP_MBID)).thenReturn(GROUP_COVER_URL);
        // The archive is down, so only what the lookup said is known.
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.unreachable());

        ReleaseDto release = service.getRelease(MB_RELEASE);

        assertThat(release.coverArtUrl()).isEqualTo(GROUP_COVER_URL);
        verify(coverArtClient, never()).frontCoverUrl(any());
    }

    @Test
    void leavesTheQuestionOpenWhenTheAlbumCouldNotBeAsked() {
        ReleaseEntity entity = mirrored();
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.absent());
        when(coverArtClient.fetchGroupThumbnail(GROUP_MBID)).thenReturn(CoverProbe.unreachable());

        ReleaseDto release = service.getRelease(MB_RELEASE);

        // The pressing's own 404 is no longer the whole answer, so nothing is written down.
        assertThat(release.coverArtUrl()).isEqualTo(COVER_URL);
        assertThat(entity.getHasCoverArt()).isNull();
        verify(releaseRepository, never()).save(any());
    }

    /**
     * A copy with no pressing chosen is mirrored under its album's id. Probing that mbid as a
     * pressing got a 404, which was remembered as "no cover", and a friend's shelf drew the
     * album blank while the owner's phone still showed it. Reported from the field.
     */
    @Test
    void probesAnAlbumRowAtTheAlbumsAddress() {
        String groupMbid = "02a544b3-0459-42c7-bd9c-047162e7b67a";
        String albumRef = "musicbrainz:" + groupMbid;
        String groupCover = "https://coverartarchive.org/release-group/" + groupMbid + "/front-500";

        ReleaseGroupEntity album = new ReleaseGroupEntity();
        album.setId(UUID.randomUUID());
        album.setExternalId(albumRef);
        album.setTitle("HIT ME HARD AND SOFT");
        album.setArtistName("Billie Eilish");
        album.setFetchedAt(Instant.now());

        ReleaseEntity entity = new ReleaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId(albumRef);
        entity.setReleaseGroupId(album.getId());
        entity.setTitle("HIT ME HARD AND SOFT");
        entity.setArtistName("Billie Eilish");
        entity.setFormat(Format.OTHER);
        entity.setCoverArtUrl(groupCover);
        entity.setHasCoverArt(null);
        entity.setFetchedAt(Instant.now());

        when(releaseRepository.findByExternalId(albumRef)).thenReturn(Optional.of(entity));
        when(releaseGroupRepository.findById(album.getId())).thenReturn(Optional.of(album));
        when(coverArtClient.fetchThumbnail(groupMbid)).thenReturn(CoverProbe.absent());
        when(coverArtClient.fetchGroupThumbnail(groupMbid)).thenReturn(CoverProbe.found(new byte[] {1}));

        ReleaseDto release = service.getRelease(albumRef);

        assertThat(release.coverArtUrl()).isEqualTo(groupCover);
        assertThat(entity.getHasCoverArt()).isTrue();
        verify(coverArtClient, never()).fetchThumbnail(any());
    }

    @Test
    void probesAPressingAtThePressingsAddress() {
        mirrored();
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.found(new byte[] {1}));

        service.getRelease(MB_RELEASE);

        verify(coverArtClient).fetchThumbnail(MBID);
        verify(coverArtClient, never()).fetchGroupThumbnail(any());
    }

    @Test
    void samplesThePaletteWhenTheCoverCameBack() {
        ReleaseEntity entity = mirrored();
        byte[] bytes = {1, 2, 3};
        when(coverArtClient.fetchThumbnail(MBID)).thenReturn(CoverProbe.found(bytes));
        when(colorExtractor.extract(bytes))
                .thenReturn(Optional.of(new CoverPalette("#101010", "#a2573a", 0.2)));

        ReleaseDto release = service.getRelease(MB_RELEASE);

        assertThat(release.coverArtUrl()).isEqualTo(COVER_URL);
        assertThat(entity.getHasCoverArt()).isTrue();
        assertThat(entity.getDominantColor()).isEqualTo("#101010");
    }
}
