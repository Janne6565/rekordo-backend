package com.rekordo.services.metadata;

import com.rekordo.client.discogs.DiscogsResponses;
import com.rekordo.model.core.Format;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscogsMapperTest {

    private static DiscogsResponses.SearchResult result(
            String title, List<String> barcode, List<DiscogsResponses.ReleaseFormat> formats, Long masterId) {
        return new DiscogsResponses.SearchResult(
                31679120L, masterId, title, 2024, "UK & Ireland", "TEN012",
                List.of("Atlantic Records UK", "The Vinyl Factory"), barcode, formats, "", "");
    }

    @Test
    void splitsArtistFromTitleOnTheFirstSeparator() {
        // Discogs sends "Artist - Title" as one string and never the two apart.
        assertThat(DiscogsMapper.artistOf("Fred again.. - Ten Days")).isEqualTo("Fred again..");
        assertThat(DiscogsMapper.titleOf("Fred again.. - Ten Days")).isEqualTo("Ten Days");

        // Splitting on the last separator would read this as an artist called
        // "Miles Davis - Bitches Brew".
        assertThat(DiscogsMapper.artistOf("Miles Davis - Bitches Brew - Live")).isEqualTo("Miles Davis");
        assertThat(DiscogsMapper.titleOf("Miles Davis - Bitches Brew - Live"))
                .isEqualTo("Bitches Brew - Live");
    }

    @Test
    void dropsDiscogsOwnBookkeepingFromTheArtistName() {
        // "(2)" is Discogs' disambiguation key -- the second Ben Howard in their database,
        // not a count of anything. Real rows from this wishlist.
        assertThat(DiscogsMapper.artistOf("Ben Howard (2) - Is It?")).isEqualTo("Ben Howard");
        assertThat(DiscogsMapper.artistOf("Berlioz (2) - Open This Wall")).isEqualTo("Berlioz");
        assertThat(DiscogsMapper.artistOf("Daughter (2) - If You Leave")).isEqualTo("Daughter");

        // The trailing asterisk is Discogs' "credited as" marker.
        assertThat(DiscogsMapper.artistOf("\u4e45\u77f3\u8b72* - \u5343\u3068\u5343\u5c0b\u306e\u795e\u96a0\u3057")).isEqualTo("\u4e45\u77f3\u8b72");

        // Both at once: the asterisk sits outside the key, so it has to come off first.
        assertThat(DiscogsMapper.artistOf("Berlioz (2)* - Open This Wall")).isEqualTo("Berlioz");

        // The title keeps everything -- an album may legitimately end in a number in
        // brackets, and only the artist half carries Discogs' keys.
        assertThat(DiscogsMapper.titleOf("Ben Howard (2) - Is It?")).isEqualTo("Is It?");
    }

    @Test
    void leavesBracketsThatBelongToTheNameAlone() {
        // Digits only, anchored to the end: a name that really ends in brackets survives.
        assertThat(DiscogsMapper.artistOf("Sunn O))) - Monoliths & Dimensions"))
                .isEqualTo("Sunn O)))");
        assertThat(DiscogsMapper.artistOf("Fred again.. - Ten Days")).isEqualTo("Fred again..");
        assertThat(DiscogsMapper.artistOf("The The (Band) - Soul Mining")).isEqualTo("The The (Band)");

        // A name that is nothing but a marker is left as it is rather than emptied out.
        assertThat(DiscogsMapper.artistOf("(2) - Untitled")).isEqualTo("(2)");
    }

    @Test
    void copesWithATitleThatHasNoArtistInIt() {
        assertThat(DiscogsMapper.artistOf("Untitled")).isEqualTo("Unknown artist");
        assertThat(DiscogsMapper.titleOf("Untitled")).isEqualTo("Untitled");
        assertThat(DiscogsMapper.artistOf(null)).isEqualTo("Unknown artist");
        assertThat(DiscogsMapper.titleOf(null)).isEqualTo("Untitled");
    }

    @Test
    void ignoresABarcodeFieldThatHoldsSomethingElse() {
        // Real data. One pressing of "ten days" lists its runout-groove text in the barcode
        // field; storing that would poison barcode lookup, which is the one identifier a
        // scan can rely on.
        var runout = result(
                "Fred again.. - Ten Days",
                List.of("VF421 / TEN012 A   ten days that meant something Stu", "VF421 / TEN012 B"),
                null,
                null);

        assertThat(DiscogsMapper.barcodeOf(runout)).isNull();
    }

    @Test
    void findsTheBarcodeAmongWhateverElseIsInThere() {
        var mixed = result(
                "Fred again.. - Ten Days", List.of("VF421 / TEN012 A", "5 054197 696855"), null, null);

        // Spaces and dashes are how people type a barcode off a sleeve.
        assertThat(DiscogsMapper.barcodeOf(mixed)).isEqualTo("5054197696855");
    }

    @Test
    void readsTheFormatFromTheFirstMedium() {
        var vinyl = result(
                "Fred again.. - Ten Days",
                null,
                List.of(new DiscogsResponses.ReleaseFormat("Vinyl", "1", List.of("LP", "Album"))),
                null);
        assertThat(DiscogsMapper.formatOf(vinyl)).isEqualTo(Format.VINYL);

        // Discogs calls a download "File"; the shared matcher already folds that to DIGITAL.
        var file = result(
                "Fred again.. - Ten Days",
                null,
                List.of(new DiscogsResponses.ReleaseFormat("File", "1", List.of("FLAC"))),
                null);
        assertThat(DiscogsMapper.formatOf(file)).isEqualTo(Format.DIGITAL);

        assertThat(DiscogsMapper.formatOf(result("x - y", null, null, null))).isEqualTo(Format.OTHER);
    }

    @Test
    void givesAnUngroupedPressingAnAlbumOfItsOwn() {
        // A release with no master is a one-off nobody has grouped yet. Keying it on the
        // release keeps it addressable; keying every such record on "no master" would lump
        // them into one nonsense album.
        assertThat(DiscogsMapper.albumRefOf(result("x - y", null, null, 3721005L)))
                .isEqualTo("discogs:3721005");
        assertThat(DiscogsMapper.albumRefOf(result("x - y", null, null, null)))
                .isEqualTo("discogs:release-31679120");
        assertThat(DiscogsMapper.albumRefOf(result("x - y", null, null, 0L)))
                .isEqualTo("discogs:release-31679120");
    }

    @Test
    void treatsAnEmptyCoverAsNoCover() {
        // Discogs returns "" rather than omitting the field when the caller has no token.
        assertThat(DiscogsMapper.coverUrlOf(result("x - y", null, null, null))).isNull();
    }
}
