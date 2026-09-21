package com.rekordo.services.metadata;

import com.rekordo.client.CoverArtClient;
import com.rekordo.client.applemusic.AppleMusicClient;
import com.rekordo.client.applemusic.AppleMusicResponses;
import com.rekordo.client.discogs.DiscogsClient;
import com.rekordo.client.discogs.DiscogsResponses;
import com.rekordo.client.musicbrainz.MusicBrainzClient;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.exception.UpstreamUnavailableException;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The search the add flow shows: records, not pressings.
 *
 * <p>The failure this replaces was not an error. Asking Discogs for releases returned ten
 * rows for a record with ten pressings -- the same sleeve and the same title, differing in
 * a catalogue number nobody has looked up yet -- and every one of them was a correct
 * answer to the wrong question.
 *
 * <p>So what is pinned here is that one record produces one row whichever catalogue
 * answered, and that the screen above cannot tell which did.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlbumSearchTest {

    @Mock private MusicBrainzClient musicBrainzClient;
    @Mock private DiscogsClient discogsClient;
    @Mock private AppleMusicClient appleMusicClient;
    @Mock private CoverArtClient coverArtClient;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private ReleaseGroupRepository releaseGroupRepository;
    @Mock private ArtistImageRepository artistImageRepository;

    @InjectMocks private MetadataService service;

    @Test
    void answersFromAppleMusicWhenItHasSomething() {
        when(appleMusicClient.searchAlbums(anyString(), anyInt()))
                .thenReturn(List.of(appleAlbum("1440857781", "Bitches Brew", "Miles Davis")));

        List<AlbumDto> albums = service.searchAlbums("bitches brew", 25);

        assertThat(albums).singleElement().satisfies(album -> {
            assertThat(album.albumId()).isEqualTo("applemusic:1440857781");
            assertThat(album.title()).isEqualTo("Bitches Brew");
            assertThat(album.coverArtTemplate()).contains("{w}x{h}");
        });
        // Discogs is not asked at all: the point of this path is one upstream, not two.
        verify(discogsClient, never()).search(anyString(), anyInt());
    }

    @Test
    void doesNotWriteAnythingDownForAnAppleAnswer() {
        // The rule that separates this from search(): an Apple album is an answer to a
        // question, not a row this app keeps. Mirroring it would make a stateless catalogue
        // permanent, which is the thing the design deliberately avoids.
        when(appleMusicClient.searchAlbums(anyString(), anyInt()))
                .thenReturn(List.of(appleAlbum("1", "x", "y")));

        service.searchAlbums("x", 25);

        verifyNoInteractions(releaseRepository, releaseGroupRepository);
    }

    @Test
    void collapsesDiscogsPressingsIntoOneRowPerRecord() {
        // The original complaint, exactly: three pressings of one record. They share a
        // master, which every search result already carries.
        when(appleMusicClient.searchAlbums(anyString(), anyInt())).thenReturn(List.of());
        when(discogsClient.search(anyString(), anyInt())).thenReturn(List.of(
                discogsResult(31679120L, 1283634L, "Daughter - If You Leave", 2013),
                discogsResult(44912233L, 1283634L, "Daughter - If You Leave", 2013),
                discogsResult(55123344L, 1283634L, "Daughter - If You Leave", 2016)));

        List<AlbumDto> albums = service.searchAlbums("if you leave", 25);

        assertThat(albums).singleElement().satisfies(album -> {
            assertThat(album.albumId()).isEqualTo("discogs:1283634");
            assertThat(album.title()).isEqualTo("If You Leave");
            assertThat(album.artistName()).isEqualTo("Daughter");
            // Nothing to resize: Discogs serves one fixed image per release.
            assertThat(album.coverArtTemplate()).isNull();
        });
    }

    @Test
    void keepsAPressingThatBelongsToNoMaster() {
        // A standalone release is a real record. Dropping it would make the search quietly
        // worse than the one it replaces, which is the one regression nobody would report.
        // albumRefOf keys it on the release itself, which is the id the rest of the app
        // already uses for a pressing nobody has grouped.
        when(appleMusicClient.searchAlbums(anyString(), anyInt())).thenReturn(List.of());
        when(discogsClient.search(anyString(), anyInt())).thenReturn(List.of(
                discogsResult(999L, null, "Some Band - A Private Pressing", 1974)));

        assertThat(service.searchAlbums("private", 25))
                .singleElement()
                .extracting(AlbumDto::albumId)
                .isEqualTo("discogs:release-999");
    }

    @Test
    void fallsBackToDiscogsWhenAppleIsUnreachable() {
        when(appleMusicClient.searchAlbums(anyString(), anyInt()))
                .thenThrow(new UpstreamUnavailableException("Apple Music", new RuntimeException()));
        when(discogsClient.search(anyString(), anyInt())).thenReturn(List.of(
                discogsResult(1L, 42L, "Daughter - If You Leave", 2013)));

        assertThat(service.searchAlbums("x", 25))
                .singleElement()
                .extracting(AlbumDto::albumId)
                .isEqualTo("discogs:42");
    }

    @Test
    void answersEmptyWhenNeitherCatalogueHasAnything() {
        // Both unreachable is still a 200 with nothing in it. A search that finds no record
        // and a search that could not run look the same to the person typing.
        when(appleMusicClient.searchAlbums(anyString(), anyInt())).thenReturn(List.of());
        when(discogsClient.search(anyString(), anyInt()))
                .thenThrow(new UpstreamUnavailableException("Discogs", new RuntimeException()));

        assertThat(service.searchAlbums("qwertyuiop", 25)).isEmpty();
    }

    private static AppleMusicResponses.Album appleAlbum(String id, String name, String artist) {
        return new AppleMusicResponses.Album(id, new AppleMusicResponses.Attributes(
                name, artist,
                new AppleMusicResponses.Artwork(
                        "https://mz/image/{w}x{h}bb.jpg", 1400, 1400, "1d1b19", "f5f1e8"),
                "1970-03-30", "Columbia", "886443689510", 8, "https://music.apple.com/x"));
    }

    private static DiscogsResponses.SearchResult discogsResult(
            Long id, Long masterId, String title, Integer year) {
        return new DiscogsResponses.SearchResult(
                id, masterId, title, year, "UK", "CAT-1", List.of("4AD"),
                List.of("1234567890123"), List.of(), "https://i.discogs.com/thumb.jpg",
                "https://i.discogs.com/cover.jpg");
    }
}
