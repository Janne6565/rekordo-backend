package com.rekordo.client;

import com.rekordo.client.applemusic.AppleArtworkClient;
import com.rekordo.client.discogs.DiscogsClient;
import com.rekordo.configuration.DiscogsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Which hosts the palette sampler may fetch a cover from.
 *
 * <p>A cover address can arrive from a client through {@code adoptFromClient}, and the
 * Discogs client attaches the Discogs token to every request it makes. So the question is
 * not "is the URL well-formed" but "is it the catalogue's own CDN" -- anything else is
 * refused before a request exists.
 */
class ImageFetchHostsTest {

    @Test
    void acceptsOnlyHttpsOnTheDomainOrItsSubdomains() {
        assertThat(ImageHosts.isHttpsOn("https://i.discogs.com/a.jpg", "discogs.com")).isTrue();
        assertThat(ImageHosts.isHttpsOn("https://discogs.com/a.jpg", "discogs.com")).isTrue();
        assertThat(ImageHosts.isHttpsOn("http://i.discogs.com/a.jpg", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn("https://evildiscogs.com/a.jpg", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn("https://i.discogs.com.evil.test/a.jpg", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn("https://i.discogs.com@evil.test/a.jpg", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn("https://x@i.discogs.com/a.jpg", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn("not a url", "discogs.com")).isFalse();
        assertThat(ImageHosts.isHttpsOn(null, "discogs.com")).isFalse();
    }

    @Test
    void theDiscogsClientNeverTakesItsTokenOffDiscogs() {
        RestClient.Builder builder = RestClient.builder().defaultHeader("Authorization", "Discogs token=secret");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscogsClient client = new DiscogsClient(
                builder.build(), new DiscogsProperties("https://api.discogs.com", "test", "secret", 60));

        CoverProbe probe = client.fetchImage("http://10.0.0.1:8080/internal");
        CoverProbe apple = client.fetchImage("https://is1-ssl.mzstatic.com/image/a/600x600bb.jpg");

        // No expectation was registered, so any request at all would fail verify().
        server.verify();
        // Refused, not "no cover": nothing was asked, so nothing may be written down.
        assertThat(probe.conclusive()).isFalse();
        assertThat(apple.conclusive()).isFalse();
    }

    @Test
    void appleArtworkIsFetchedFromApplesCdnWithoutACredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AppleArtworkClient client = new AppleArtworkClient(builder.build());
        server.expect(requestTo("https://is1-ssl.mzstatic.com/image/a/250x250bb.jpg"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(new byte[] {1, 2}, MediaType.IMAGE_JPEG));

        CoverProbe found = client.fetch("https://is1-ssl.mzstatic.com/image/a/{w}x{h}bb.jpg");
        CoverProbe refused = client.fetch("https://evil.test/a.jpg");

        server.verify();
        assertThat(found.found()).isTrue();
        assertThat(refused.conclusive()).isFalse();
    }
}
