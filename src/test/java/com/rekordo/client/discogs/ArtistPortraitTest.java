package com.rekordo.client.discogs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second step between "a MusicBrainz artist" and "a picture of them": choosing which
 * of Discogs' images is the portrait.
 *
 * <p>A place where a wrong answer looks like a right one. The wrong image out of a set
 * puts a record sleeve where a face should be, and nothing throws. The first step is
 * {@code com.rekordo.client.musicbrainz.DiscogsRelationTest}; the two are apart only
 * because each reaches a package-private seam in its own client.
 */
class ArtistPortraitTest {

    private static DiscogsResponses.ArtistImage image(String type, String uri, String uri150) {
        return new DiscogsResponses.ArtistImage(type, uri, uri150);
    }

    @Test
    void prefersThePrimaryImageOverTheRest() {
        // Secondaries are live shots, logos and sleeve scans — wrong for a 46px circle.
        Optional<String> chosen = DiscogsClient.preferredImage(List.of(
                image("secondary", "https://i.discogs.com/live.jpg", "https://i.discogs.com/live-150.jpg"),
                image("primary", "https://i.discogs.com/band.jpg", "https://i.discogs.com/band-150.jpg")));

        assertThat(chosen).contains("https://i.discogs.com/band-150.jpg");
    }

    @Test
    void fallsBackToTheFullImageWhenThereIsNoThumbnail() {
        assertThat(DiscogsClient.preferredImage(List.of(image("primary", "https://i.discogs.com/band.jpg", ""))))
                .contains("https://i.discogs.com/band.jpg");
    }

    @Test
    void treatsDiscogsBlankStringsAsNoPicture() {
        // Blank rather than absent is how Discogs answers an unauthenticated caller. Handing
        // that to an <img> would render a broken image instead of the initial.
        assertThat(DiscogsClient.preferredImage(List.of(image("primary", "", "")))).isEmpty();
        assertThat(DiscogsClient.preferredImage(List.of())).isEmpty();
        assertThat(DiscogsClient.preferredImage(null)).isEmpty();
    }
}
