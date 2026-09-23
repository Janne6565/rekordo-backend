package com.rekordo.services.metadata;

import com.rekordo.client.CoverArtClient;
import com.rekordo.client.applemusic.AppleMusicClient;
import com.rekordo.client.applemusic.AppleMusicResponses;
import com.rekordo.client.discogs.DiscogsClient;
import com.rekordo.client.discogs.DiscogsResponses;
import com.rekordo.client.musicbrainz.MusicBrainzClient;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.entity.ReleaseGroupEntity;
import com.rekordo.model.core.AlbumCoverDto;
import com.rekordo.model.core.Format;
import com.rekordo.repository.ArtistImageRepository;
import com.rekordo.repository.ReleaseGroupRepository;
import com.rekordo.repository.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The album artwork a wishlist row draws.
 *
 * <p>The rule under test is "resolve, never fetch": every answer comes out of the mirror,
 * because a list of albums is a screen and not a reason to call two catalogues.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlbumCoversTest {

    private static final String MB_ALBUM = "musicbrainz:0f2d5a1e-4a1e-4e7a-9c1e-2f0d4b6a8c11";
    private static final String DISCOGS_ALBUM = "discogs:1283634";
    private static final String APPLE_ALBUM = "applemusic:590434066";

    @Mock private MusicBrainzClient musicBrainzClient;
    @Mock private DiscogsClient discogsClient;
    @Mock private AppleMusicClient appleMusicClient;
    @Mock private CoverArtClient coverArtClient;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private ReleaseGroupRepository releaseGroupRepository;
    @Mock private ArtistImageRepository artistImageRepository;

    @InjectMocks private MetadataService service;

    private static ReleaseGroupEntity group(String externalId) {
        ReleaseGroupEntity entity = new ReleaseGroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId(externalId);
        entity.setTitle("One More Light Live");
        entity.setArtistName("Linkin Park");
        entity.setFetchedAt(Instant.now());
        return entity;
    }

    private static ReleaseEntity release(
            ReleaseGroupEntity album, String externalId, String coverArtUrl, Boolean hasCoverArt) {
        ReleaseEntity entity = new ReleaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId(externalId);
        entity.setReleaseGroupId(album.getId());
        entity.setTitle("One More Light Live");
        entity.setArtistName("Linkin Park");
        entity.setFormat(Format.VINYL);
        entity.setCoverArtUrl(coverArtUrl);
        entity.setHasCoverArt(hasCoverArt);
        entity.setFetchedAt(Instant.now());
        return entity;
    }

    private void mirror(Collection<ReleaseGroupEntity> groups, List<ReleaseEntity> releases) {
        when(releaseGroupRepository.findAllByExternalIdIn(any())).thenReturn(List.copyOf(groups));
        when(releaseRepository.findAllByReleaseGroupIdIn(any())).thenReturn(releases);
    }

    @Test
    void takesTheCoverOfAMirroredPressing() {
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        mirror(List.of(album), List.of(release(album, "discogs:9", "https://img.discogs/9.jpg", true)));

        List<AlbumCoverDto> covers = service.albumCovers(List.of(DISCOGS_ALBUM));

        assertThat(covers).containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, "https://img.discogs/9.jpg"));
        verify(discogsClient, never()).search(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void prefersAPressingKnownToHaveArtOverAnUnprobedOne() {
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        mirror(
                List.of(album),
                List.of(
                        release(album, "discogs:1", "https://img.discogs/1.jpg", null),
                        release(album, "discogs:2", "https://img.discogs/2.jpg", true)));

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, "https://img.discogs/2.jpg"));
    }

    @Test
    void prefersTheArchivesAlbumAddressOverAnUnprobedPressing() {
        // Reported from the examples plate: an album with two dozen illustrated pressings
        // drew an empty square. The mirror held one MusicBrainz pressing, nobody had probed
        // it, and its constructed Cover Art Archive URL happened to have nothing behind it.
        // The release *group* address resolves to whichever release actually has a picture,
        // so it is the better guess whenever the pressing's own is only a guess.
        ReleaseGroupEntity album = group(MB_ALBUM);
        mirror(
                List.of(album),
                List.of(release(album, "musicbrainz:80032220", "https://coverartarchive.org/release/80032220/front-500", null)));
        when(coverArtClient.frontCoverUrlForGroup(any()))
                .thenAnswer(call -> "https://coverartarchive.org/release-group/" + call.getArgument(0) + "/front-500");

        assertThat(service.albumCovers(List.of(MB_ALBUM)))
                .containsExactly(new AlbumCoverDto(
                        MB_ALBUM,
                        "https://coverartarchive.org/release-group/0f2d5a1e-4a1e-4e7a-9c1e-2f0d4b6a8c11/front-500"));
    }

    @Test
    void keepsAConfirmedPressingAheadOfTheAlbumAddress() {
        ReleaseGroupEntity album = group(MB_ALBUM);
        mirror(
                List.of(album),
                List.of(release(album, "musicbrainz:1", "https://coverartarchive.org/release/1/front-500", true)));

        assertThat(service.albumCovers(List.of(MB_ALBUM)))
                .containsExactly(new AlbumCoverDto(MB_ALBUM, "https://coverartarchive.org/release/1/front-500"));
        // No need to ask the archive about the group: a pressing already answered for certain.
        verify(coverArtClient, never()).frontCoverUrlForGroup(any());
    }

    @Test
    void stillDrawsAnUnprobedPressingWhenTheAlbumHasNoAddressOfItsOwn() {
        // A Discogs album publishes no per-album image, so an unprobed pressing is the only
        // answer there is -- better than a placeholder.
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        mirror(List.of(album), List.of(release(album, "discogs:1", "https://img.discogs/1.jpg", null)));
        when(discogsClient.servesImages()).thenReturn(false);

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, "https://img.discogs/1.jpg"));
    }

    @Test
    void ignoresAPressingKnownToHaveNoArt() {
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        mirror(List.of(album), List.of(release(album, "discogs:1", "https://img.discogs/1.jpg", false)));

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, null));
    }

    @Test
    void fallsBackToTheArchiveForAnUnmirroredMusicBrainzAlbum() {
        mirror(List.of(), List.of());
        when(coverArtClient.frontCoverUrlForGroup(any()))
                .thenAnswer(call -> "https://coverartarchive.org/release-group/" + call.getArgument(0) + "/front-500");

        assertThat(service.albumCovers(List.of(MB_ALBUM)))
                .containsExactly(new AlbumCoverDto(
                        MB_ALBUM,
                        "https://coverartarchive.org/release-group/0f2d5a1e-4a1e-4e7a-9c1e-2f0d4b6a8c11/front-500"));
    }

    @Test
    void asksDiscogsForAnAlbumNoMirroredPressingCanDescribe() {
        // The gap an imported collection falls into: the wish names an album this
        // deployment never searched for, so no pressing of it is mirrored. A MusicBrainz
        // album still resolved through the archive; a Discogs one had nowhere to go.
        mirror(List.of(), List.of());
        when(discogsClient.servesImages()).thenReturn(true);
        when(discogsClient.master(1283634L)).thenReturn(Optional.of(
                new DiscogsResponses.MasterResponse(
                        1283634L,
                        "One More Light Live",
                        List.of(new DiscogsResponses.ArtistCredit("Linkin Park")),
                        List.of())));
        when(discogsClient.coverOf(any())).thenReturn(Optional.of("https://img.discogs/master.jpg"));
        when(releaseGroupRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, "https://img.discogs/master.jpg"));

        // Remembered against the album, so the next reader pays nothing.
        ArgumentCaptor<ReleaseGroupEntity> saved = ArgumentCaptor.forClass(ReleaseGroupEntity.class);
        verify(releaseGroupRepository).save(saved.capture());
        assertThat(saved.getValue().getCoverArtUrl()).isEqualTo("https://img.discogs/master.jpg");
        assertThat(saved.getValue().getCoverFetchedAt()).isNotNull();
    }

    @Test
    void neverAsksTwiceAboutAnAlbumWithNoCover() {
        // "Asked, and there is none" has to be tellable from "never asked", or every
        // reader of an artless album goes upstream again forever.
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        album.setCoverFetchedAt(Instant.now());
        album.setCoverArtUrl(null);
        mirror(List.of(album), List.of());

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, null));
        verify(discogsClient, never()).master(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void goesUpstreamForAtMostAHandfulOfAlbumsPerRequest() {
        // An open endpoint in front of a catalogue paced at tens of requests a minute.
        // A page of unknown albums must not become a page of blocking calls.
        mirror(List.of(), List.of());
        when(discogsClient.servesImages()).thenReturn(true);
        when(discogsClient.master(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.empty());

        List<String> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i += 1) {
            many.add("discogs:" + (1000 + i));
        }
        assertThat(service.albumCovers(many)).hasSize(40);

        verify(discogsClient, org.mockito.Mockito.times(8))
                .master(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void asksNothingUpstreamForAPressingWithNoMaster() {
        // "discogs:release-<id>" is the album ref for a pressing Discogs lists no master
        // for. There is no master to ask about.
        mirror(List.of(), List.of());
        when(discogsClient.servesImages()).thenReturn(true);

        assertThat(service.albumCovers(List.of("discogs:release-556677")))
                .containsExactly(new AlbumCoverDto("discogs:release-556677", null));
        verify(discogsClient, never()).master(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void leavesOutHandEnteredAlbumsAndAsksForNothingTwice() {
        ReleaseGroupEntity album = group(DISCOGS_ALBUM);
        mirror(List.of(album), List.of(release(album, "discogs:9", "https://img.discogs/9.jpg", true)));

        List<AlbumCoverDto> covers = service.albumCovers(
                List.of("local:" + UUID.randomUUID(), DISCOGS_ALBUM, DISCOGS_ALBUM));

        assertThat(covers).containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, "https://img.discogs/9.jpg"));
    }

    @Test
    void answersNullForAnUnmirroredDiscogsAlbum() {
        mirror(List.of(), List.of());

        assertThat(service.albumCovers(List.of(DISCOGS_ALBUM)))
                .containsExactly(new AlbumCoverDto(DISCOGS_ALBUM, null));
        verify(coverArtClient, never()).frontCoverUrlForGroup(any());
    }

    @Test
    void asksAppleForAnAlbumPickedFromTheAppleSearch() {
        // Reported from a friend's view of a wishlist: the owner saw the sleeve, because
        // their device kept the search result's artwork, and everybody else saw none --
        // an Apple album is never mirrored, so this endpoint had nothing to answer with.
        mirror(List.of(), List.of());
        when(appleMusicClient.album("590434066")).thenReturn(Optional.of(new AppleMusicResponses.Album(
                "590434066",
                new AppleMusicResponses.Attributes(
                        "A Thousand Suns (Deluxe Edition)",
                        "LINKIN PARK",
                        new AppleMusicResponses.Artwork("https://is1.mzstatic.com/a/{w}x{h}bb.jpg", 3000, 3000, null, null),
                        "2010-09-14",
                        null,
                        null,
                        null,
                        "https://music.apple.com/album/590434066"))));

        assertThat(service.albumCovers(List.of(APPLE_ALBUM)))
                .containsExactly(new AlbumCoverDto(APPLE_ALBUM, "https://is1.mzstatic.com/a/600x600bb.jpg"));

        ArgumentCaptor<ReleaseGroupEntity> saved = ArgumentCaptor.forClass(ReleaseGroupEntity.class);
        verify(releaseGroupRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalId()).isEqualTo(APPLE_ALBUM);
        assertThat(saved.getValue().getCoverArtUrl()).isEqualTo("https://is1.mzstatic.com/a/600x600bb.jpg");
        assertThat(saved.getValue().getCoverFetchedAt()).isNotNull();
    }

    @Test
    void neverAsksAppleTwiceAboutTheSameAlbum() {
        ReleaseGroupEntity album = group(APPLE_ALBUM);
        album.setCoverArtUrl("https://is1.mzstatic.com/a/600x600bb.jpg");
        album.setCoverFetchedAt(Instant.now());
        mirror(List.of(album), List.of());

        assertThat(service.albumCovers(List.of(APPLE_ALBUM)))
                .containsExactly(new AlbumCoverDto(APPLE_ALBUM, "https://is1.mzstatic.com/a/600x600bb.jpg"));
        verify(appleMusicClient, never()).album(any());
    }
}
