package com.rekordo.client.musicbrainz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first step between "a MusicBrainz artist" and "a picture of them": reading the
 * Discogs artist id off the relation.
 *
 * <p>A place where a wrong answer looks like a right one. A misparsed URL resolves to a
 * real Discogs artist who is somebody else, and nothing throws. The second step is
 * {@code com.rekordo.client.discogs.ArtistPortraitTest}; the two are apart only because
 * each reaches a package-private seam in its own client.
 */
class DiscogsRelationTest {

    @Test
    void readsTheArtistIdOffADiscogsRelation() {
        assertThat(MusicBrainzClient.trailingId("https://www.discogs.com/artist/1055923"))
                .contains(1055923L);
    }

    @Test
    void readsTheArtistIdWhenTheUrlCarriesASlug() {
        // Both shapes are in MusicBrainz; the slug is decoration and the number is the id.
        assertThat(MusicBrainzClient.trailingId("https://www.discogs.com/artist/1055923-Daughter"))
                .contains(1055923L);
    }

    @Test
    void refusesADiscogsUrlThatIsNotAnArtist() {
        // A master or label URL would parse to a number that means something else entirely.
        assertThat(MusicBrainzClient.trailingId("https://www.discogs.com/master/12345")).isEmpty();
        assertThat(MusicBrainzClient.trailingId("https://www.discogs.com/label/678")).isEmpty();
        assertThat(MusicBrainzClient.trailingId("https://en.wikipedia.org/wiki/Daughter")).isEmpty();
    }
}
