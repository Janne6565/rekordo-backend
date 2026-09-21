package com.rekordo.services.metadata;

import com.rekordo.client.applemusic.AppleMusicResponses;
import com.rekordo.model.core.AlbumDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Apple album on its way to the search list.
 *
 * <p>Half of Apple's album attributes are optional, and the ones this app most wants --
 * the year, the label, the barcode -- are among them. A mapper that assumes otherwise does
 * not fail on the record that is missing them; it fails on whichever search happens to
 * return one, which is every search eventually.
 */
class AppleMusicMapperTest {

    @Test
    void readsAFullAlbum() {
        AlbumDto album = AppleMusicMapper.toAlbumDto(album(
                "1440857781", "Bitches Brew", "Miles Davis", "1970-03-30",
                artwork("https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg")));

        assertThat(album.albumId()).isEqualTo("applemusic:1440857781");
        assertThat(album.title()).isEqualTo("Bitches Brew");
        assertThat(album.artistName()).isEqualTo("Miles Davis");
        assertThat(album.year()).isEqualTo(1970);
    }

    @Test
    void keepsTheTemplateAndResolvesOneUsableUrlBesideIt() {
        // Both fields, on purpose: a client written before the template still reads
        // coverArtUrl, and one written after asks for the size it actually needs.
        AlbumDto album = AppleMusicMapper.toAlbumDto(album(
                "1", "x", "y", "1970", artwork("https://mz/image/{w}x{h}bb.jpg")));

        assertThat(album.coverArtTemplate()).isEqualTo("https://mz/image/{w}x{h}bb.jpg");
        assertThat(album.coverArtUrl()).isEqualTo("https://mz/image/600x600bb.jpg");
    }

    @Test
    void readsAYearOutOfADateThatIsOnlyAYear() {
        // Apple's releaseDate is partial as often as MusicBrainz's. Date-parsing it throws.
        assertThat(AppleMusicMapper.toAlbumDto(
                album("1", "x", "y", "1970", artwork("https://mz/{w}x{h}.jpg"))).year())
                .isEqualTo(1970);
    }

    @Test
    void survivesAnAlbumWithNoReleaseDateAtAll() {
        AlbumDto album = AppleMusicMapper.toAlbumDto(
                album("1", "x", "y", null, artwork("https://mz/{w}x{h}.jpg")));

        assertThat(album.year()).isNull();
    }

    @Test
    void namesAnAlbumThatApplesentWithoutOne() {
        // Neither is nullable in Apple's schema, but a null here would reach the shelf as a
        // blank row rather than an error, and a blank row is not reportable by a user.
        AlbumDto album = AppleMusicMapper.toAlbumDto(
                album("1", null, null, "1970", artwork("https://mz/{w}x{h}.jpg")));

        assertThat(album.title()).isEqualTo("Untitled");
        assertThat(album.artistName()).isEqualTo("Unknown artist");
    }

    @Test
    void hasNoArtworkWhenAppleSentNone() {
        AlbumDto album = AppleMusicMapper.toAlbumDto(album("1", "x", "y", "1970", null));

        assertThat(album.coverArtUrl()).isNull();
        assertThat(album.coverArtTemplate()).isNull();
    }

    @Test
    void dropsARowWithNothingToIdentifyIt() {
        assertThat(AppleMusicMapper.toAlbumDto(null)).isNull();
        assertThat(AppleMusicMapper.toAlbumDto(
                new AppleMusicResponses.Album(null, null))).isNull();
        assertThat(AppleMusicMapper.toAlbumDto(
                new AppleMusicResponses.Album("1", null))).isNull();
    }

    @Test
    void leavesAUrlThatIsNotATemplateAlone() {
        // A working fixed URL beats a mangled resizable one.
        assertThat(AppleMusicMapper.sized("https://mz/cover.jpg", 600))
                .isEqualTo("https://mz/cover.jpg");
        assertThat(AppleMusicMapper.sized(null, 600)).isNull();
        assertThat(AppleMusicMapper.sized("  ", 600)).isNull();
    }

    private static AppleMusicResponses.Artwork artwork(String url) {
        return new AppleMusicResponses.Artwork(url, 1400, 1400, "1d1b19", "f5f1e8");
    }

    private static AppleMusicResponses.Album album(
            String id, String name, String artist, String releaseDate,
            AppleMusicResponses.Artwork artwork) {
        return new AppleMusicResponses.Album(id, new AppleMusicResponses.Attributes(
                name, artist, artwork, releaseDate, "Columbia", "886443689510", 8,
                "https://music.apple.com/de/album/" + id));
    }
}
