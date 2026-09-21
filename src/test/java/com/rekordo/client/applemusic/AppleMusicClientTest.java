package com.rekordo.client.applemusic;

import com.rekordo.configuration.AppleMusicProperties;
import com.rekordo.model.exception.UpstreamUnavailableException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The search Apple answers, and the four shapes of answer that are not a list of albums.
 *
 * <p>What is worth pinning here is not the happy path. It is that a no-match arrives as a
 * missing key rather than an empty array, that half the album attributes are optional and
 * routinely absent, and that the artwork URL is a template which must survive untouched.
 * Each of those reads as a working response right up to the point something dereferences
 * it.
 */
class AppleMusicClientTest {

    private static final String BASE = "https://apple.example.test";

    private MockRestServiceServer server;

    private AppleMusicClient client(String privateKey) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new AppleMusicClient(
                builder.build(),
                new AppleMusicProperties(BASE, "TEAM", "KEY", privateKey, "de"));
    }

    private AppleMusicClient client() {
        return client("a-key");
    }

    @Test
    void readsTheAlbumsOutOfASearchResponse() {
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE + "/v1/catalog/de/search")))
                // Encoded, not raw: the matcher reads the query string as sent, and a bare
                // space there would be a malformed request rather than a search.
                .andExpect(queryParam("term", "bitches%20brew"))
                .andExpect(queryParam("types", "albums"))
                .andRespond(withSuccess(oneAlbum(), MediaType.APPLICATION_JSON));

        List<AppleMusicResponses.Album> albums = client.searchAlbums("bitches brew", 25);

        assertThat(albums).hasSize(1);
        assertThat(albums.getFirst().id()).isEqualTo("1440857781");
        assertThat(albums.getFirst().attributes().name()).isEqualTo("Bitches Brew");
        assertThat(albums.getFirst().attributes().artistName()).isEqualTo("Miles Davis");
    }

    @Test
    void keepsTheArtworkTemplateExactlyAsAppleSentIt() {
        // The {w}x{h} placeholders are the whole value of the field: a caller asks for the
        // size it needs. Substituting or escaping them here would hand every surface the
        // same fixed image and quietly undo the reason this source was chosen.
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE)))
                .andRespond(withSuccess(oneAlbum(), MediaType.APPLICATION_JSON));

        assertThat(client.searchAlbums("x", 25).getFirst().attributes().artwork().url())
                .isEqualTo("https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg");
    }

    @Test
    void treatsAMissingAlbumsKeyAsNoMatches() {
        // Apple omits the key entirely rather than sending an empty array, so this is what
        // a search for nonsense actually looks like coming back.
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE)))
                .andRespond(withSuccess("{\"results\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.searchAlbums("qwertyuiop", 25)).isEmpty();
    }

    @Test
    void readsAnAlbumThatHasNoReleaseDate() {
        // releaseDate, recordLabel and upc are all optional in Apple's schema. An album
        // missing them is ordinary, not broken, and must not take the search down with it.
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE)))
                .andRespond(withSuccess("""
                    {"results":{"albums":{"data":[{"id":"1","attributes":{
                      "name":"Untitled","artistName":"Unknown","trackCount":1,
                      "artwork":{"url":"https://x/{w}x{h}bb.jpg","width":1,"height":1},
                      "url":"https://music.apple.com/de/album/1"}}]}}}
                    """, MediaType.APPLICATION_JSON));

        var attributes = client.searchAlbums("x", 25).getFirst().attributes();

        assertThat(attributes.name()).isEqualTo("Untitled");
        assertThat(attributes.releaseDate()).isNull();
        assertThat(attributes.recordLabel()).isNull();
        assertThat(attributes.upc()).isNull();
    }

    @Test
    void neverAsksAppleForMoreThanAPageItWillServe() {
        // Our own endpoint allows up to 50. Apple answers 400 to that, which would turn a
        // generous limit into a failed search rather than a shorter one.
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE)))
                .andExpect(queryParam("limit", "25"))
                .andRespond(withSuccess(oneAlbum(), MediaType.APPLICATION_JSON));

        client.searchAlbums("x", 50);

        server.verify();
    }

    @Test
    void reportsAnUnreachableAppleAsAnUpstreamFailure() {
        AppleMusicClient client = client();
        server.expect(requestTo(Matchers.startsWith(BASE))).andRespond(withServerError());

        assertThatThrownBy(() -> client.searchAlbums("x", 25))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("Apple Music");
    }

    @Test
    void doesNotCallAppleAtAllWithoutASigningKey() {
        // Local development, and every deployment before the secret lands. A request would
        // be refused anyway; not making it is what lets the caller fall back to Discogs.
        AppleMusicClient client = client("");

        assertThat(client.searchAlbums("x", 25)).isEmpty();

        server.verify();
    }

    private static String oneAlbum() {
        return """
            {"results":{"albums":{"href":"/v1/catalog/de/search","data":[
              {"id":"1440857781","type":"albums",
               "attributes":{"name":"Bitches Brew","artistName":"Miles Davis",
                 "artwork":{"width":1400,"height":1400,
                   "url":"https://is1-ssl.mzstatic.com/image/thumb/x/{w}x{h}bb.jpg",
                   "bgColor":"1d1b19","textColor1":"f5f1e8","textColor2":"d9d2c4"},
                 "genreNames":["Jazz"],"releaseDate":"1970-03-30",
                 "recordLabel":"Columbia","upc":"886443689510","trackCount":8,
                 "isSingle":false,"isCompilation":false,
                 "url":"https://music.apple.com/de/album/bitches-brew/1440857781"}}]}}}
            """;
    }
}
