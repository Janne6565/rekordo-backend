package com.rekordo.services.metadata;

import com.rekordo.client.musicbrainz.MusicBrainzResponses;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.model.core.AlbumDto;
import com.rekordo.model.core.ArtistDto;
import com.rekordo.model.core.Format;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMapperTest {

    private static MusicBrainzResponses.Release release(
            List<MusicBrainzResponses.ArtistCredit> credits,
            List<MusicBrainzResponses.LabelInfo> labels,
            List<MusicBrainzResponses.Medium> media,
            String date) {
        return new MusicBrainzResponses.Release(
                "b0a6f7a4-0000-4000-8000-000000000001",
                "Remain in Light",
                date,
                "US",
                "075992609524",
                credits,
                new MusicBrainzResponses.ReleaseGroup(
                        "b0a6f7a4-0000-4000-8000-000000000002", "Remain in Light", "1980", "Album", null),
                labels,
                media,
                null,
                null);
    }

    @Test
    void joinsMultipleArtistCredits() {
        var credits = List.of(
                new MusicBrainzResponses.ArtistCredit("Brian Eno", null),
                new MusicBrainzResponses.ArtistCredit("David Byrne", null));

        assertThat(MetadataMapper.artistName(release(credits, null, null, "1981")))
                .isEqualTo("Brian Eno, David Byrne");
    }

    @Test
    void survivesAReleaseWithNoArtistCredit() {
        assertThat(MetadataMapper.artistName(release(null, null, null, "1980"))).isEqualTo("Unknown artist");
    }

    @Test
    void readsTheYearOutOfPartialDates() {
        // MusicBrainz dates are partial far more often than they are complete.
        assertThat(MetadataMapper.year("1980-10-08")).isEqualTo(1980);
        assertThat(MetadataMapper.year("1980-10")).isEqualTo(1980);
        assertThat(MetadataMapper.year("1980")).isEqualTo(1980);
    }

    @Test
    void treatsUnusableDatesAsUnknown() {
        assertThat(MetadataMapper.year(null)).isNull();
        assertThat(MetadataMapper.year("")).isNull();
        assertThat(MetadataMapper.year("19")).isNull();
        assertThat(MetadataMapper.year("no-date")).isNull();
    }

    @Test
    void takesTheFormatFromTheFirstMedium() {
        var media = List.of(new MusicBrainzResponses.Medium("12\" Vinyl", null, null, null, null, null), new MusicBrainzResponses.Medium("CD", null, null, null, null, null));

        assertThat(MetadataMapper.format(release(null, null, media, "1980"))).isEqualTo(Format.VINYL);
    }

    @Test
    void readsLabelAndCatalogNumberTogether() {
        var labels = List.of(new MusicBrainzResponses.LabelInfo(
                "SRK 6095", new MusicBrainzResponses.Label("Sire")));
        var r = release(null, labels, null, "1980");

        assertThat(MetadataMapper.label(r)).isEqualTo("Sire");
        assertThat(MetadataMapper.catalogNumber(r)).isEqualTo("SRK 6095");
    }

    @Test
    void skipsEmptyLabelEntries() {
        // MusicBrainz emits placeholder label-info objects with both fields null.
        var labels = List.of(
                new MusicBrainzResponses.LabelInfo(null, null),
                new MusicBrainzResponses.LabelInfo("SRK 6095", new MusicBrainzResponses.Label("Sire")));

        assertThat(MetadataMapper.catalogNumber(release(null, labels, null, "1980"))).isEqualTo("SRK 6095");
    }

    @Test
    void reportsNoLabelRatherThanFailing() {
        assertThat(MetadataMapper.label(release(null, null, null, "1980"))).isNull();
        assertThat(MetadataMapper.catalogNumber(release(null, List.of(), null, "1980"))).isNull();
    }

    @Test
    void withholdsTheCoverUrlOnlyWhenItIsKnownToBeEmpty() {
        // The URL is built from the mbid and exists whether or not the archive holds any
        // bytes, so a definite "no cover" has to be the only thing that nulls it out.
        assertThat(MetadataMapper.toDto(releaseEntity(Boolean.FALSE), GROUP).coverArtUrl()).isNull();
        assertThat(MetadataMapper.toDto(releaseEntity(Boolean.TRUE), GROUP).coverArtUrl()).isEqualTo(COVER_URL);
        // Unknown is not a no: a release persisted from a search has never been probed, and
        // hiding a cover that does exist is the worse of the two mistakes.
        assertThat(MetadataMapper.toDto(releaseEntity(null), GROUP).coverArtUrl()).isEqualTo(COVER_URL);
    }

    @Test
    void carriesTheDisambiguationThatTellsTwoArtistsApart() {
        // MusicBrainz holds several artists called "Daughter". Without this line a row for
        // the UK band is indistinguishable from a row for the punk band or the Japanese one.
        var artist = new MusicBrainzResponses.Artist(
                "f9a1f0f0-0000-4000-8000-000000000003",
                "Daughter",
                "UK indie folk band fronted by Elena Tonra",
                "Group",
                "GB",
                100,
                new MusicBrainzResponses.LifeSpan("2010", null, false));

        ArtistDto dto = MetadataMapper.toArtistDto(artist);

        assertThat(dto.name()).isEqualTo("Daughter");
        assertThat(dto.disambiguation()).isEqualTo("UK indie folk band fronted by Elena Tonra");
        assertThat(dto.type()).isEqualTo("Group");
        assertThat(dto.beganIn()).isEqualTo("2010");
        assertThat(dto.endedIn()).isNull();
        assertThat(dto.score()).isEqualTo(100);
    }

    @Test
    void givesAnArtistWithNoDisambiguationAnEmptyOneRatherThanNull() {
        var artist = new MusicBrainzResponses.Artist(
                "f9a1f0f0-0000-4000-8000-000000000004", "Talking Heads", null, null, null, 92, null);

        // A client would otherwise have to guard every render of it.
        assertThat(MetadataMapper.toArtistDto(artist).disambiguation()).isEmpty();
    }

    @Test
    void dropsAnArtistWithNothingToIdentifyIt() {
        assertThat(MetadataMapper.toArtistDto(null)).isNull();
        assertThat(MetadataMapper.toArtistDto(
                        new MusicBrainzResponses.Artist(null, "Nameless", null, null, null, null, null)))
                .isNull();
    }

    @Test
    void takesTheAlbumsYearFromItsFirstRelease() {
        var group = new MusicBrainzResponses.ReleaseGroup(
                "a9e30282-0000-4000-8000-000000000005",
                "Bitches Brew",
                "1970-03-30",
                "Album",
                List.of(new MusicBrainzResponses.ArtistCredit("Miles Davis", null)));

        AlbumDto album = MetadataMapper.toAlbumDto(group, "https://example.test/front-500");

        assertThat(album.title()).isEqualTo("Bitches Brew");
        assertThat(album.artistName()).isEqualTo("Miles Davis");
        assertThat(album.year()).isEqualTo(1970);
        assertThat(album.primaryType()).isEqualTo("Album");
    }

    @Test
    void countsDiscsAcrossEveryMedium() {
        // A 2xLP is one release with two discs, and the pressing table says so.
        var twoDiscs = List.of(new MusicBrainzResponses.Medium("12\" Vinyl", null, null, 2, 6, null));
        assertThat(MetadataMapper.discCount(release(null, null, twoDiscs, "1970"))).isEqualTo(2);

        // A medium that does not report a disc count is still a disc.
        var unreported = List.of(
                new MusicBrainzResponses.Medium("CD", null, null, null, 9, null),
                new MusicBrainzResponses.Medium("DVD", null, null, null, 3, null));
        assertThat(MetadataMapper.discCount(release(null, null, unreported, "1999"))).isEqualTo(2);

        assertThat(MetadataMapper.discCount(release(null, null, null, "1970"))).isNull();
    }

    private static final String GROUP = "musicbrainz:b0a6f7a4-0000-4000-8000-000000000002";
    private static final String COVER_URL =
            "https://coverartarchive.org/release/b0a6f7a4-0000-4000-8000-000000000001/front-500";

    private static ReleaseEntity releaseEntity(Boolean hasCoverArt) {
        ReleaseEntity entity = new ReleaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setExternalId("musicbrainz:b0a6f7a4-0000-4000-8000-000000000001");
        entity.setTitle("Remain in Light");
        entity.setArtistName("Talking Heads");
        entity.setFormat(Format.VINYL);
        entity.setCoverArtUrl(COVER_URL);
        entity.setHasCoverArt(hasCoverArt);
        return entity;
    }

    @Test
    void track_count_falls_back_to_the_media_when_the_payload_states_no_total() {
        var lookup = release(
                List.of(),
                List.of(),
                List.of(
                        new MusicBrainzResponses.Medium("CD", null, null, null, 9, null),
                        new MusicBrainzResponses.Medium("DVD", null, null, null, 3, null)),
                "1980");

        // A search states the total; a lookup states it per medium and nothing at the top.
        // Both land in the same column, and the tracklist header reads it before its rows
        // have arrived — so a lookup-persisted release must not sit there at null.
        assertThat(MetadataMapper.trackCount(lookup)).isEqualTo(12);
    }
}
