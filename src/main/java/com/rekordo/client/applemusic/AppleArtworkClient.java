package com.rekordo.client.applemusic;

import com.rekordo.client.CoverProbe;
import com.rekordo.client.ImageHosts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;

/**
 * Fetches Apple Music artwork bytes, for palette sampling.
 *
 * <p>Its own client because the artwork lives on Apple's image CDN, not the API host, and
 * needs no credential: the API client attaches the developer token to every request, and
 * the Discogs client -- which Apple rows used to go through -- attaches the Discogs token.
 * Neither belongs on a request to a CDN.
 */
@Component
public class AppleArtworkClient {

    private static final Logger log = LoggerFactory.getLogger(AppleArtworkClient.class);

    /** Apple's artwork CDN ({@code is1-ssl.mzstatic.com} and its siblings). */
    static final String ARTWORK_DOMAIN = "mzstatic.com";

    /** The palette sampler needs a thumbnail, not the 600px sleeve a row usually names. */
    private static final int SAMPLE_SIZE = 250;

    private final RestClient restClient;

    public AppleArtworkClient(RestClient appleArtworkRestClient) {
        this.restClient = appleArtworkRestClient;
    }

    /**
     * The bytes behind one artwork URL, or why there are none.
     *
     * <p>An address that is not on Apple's CDN is refused without a request, and reported
     * as unreachable rather than absent: nothing was asked, so nothing is known, and
     * "absent" would take the picture off the row for every client.
     */
    public CoverProbe fetch(String url) {
        if (url == null || url.isBlank()) {
            return CoverProbe.absent();
        }
        // A template that kept its placeholders is sized here rather than sent with braces,
        // which are not legal in a URI and would fail the host check below.
        String sized = url.replace("{w}", String.valueOf(SAMPLE_SIZE)).replace("{h}", String.valueOf(SAMPLE_SIZE));
        if (!ImageHosts.isHttpsOn(sized, ARTWORK_DOMAIN)) {
            log.debug("Not fetching artwork from outside Apple's CDN: {}", url);
            return CoverProbe.unreachable();
        }
        try {
            byte[] bytes = restClient.get().uri(URI.create(sized)).retrieve().body(byte[].class);
            return bytes == null ? CoverProbe.absent() : CoverProbe.found(bytes);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Gone e) {
            log.debug("Apple artwork {} is gone", url);
            return CoverProbe.absent();
        } catch (IllegalArgumentException e) {
            return CoverProbe.absent();
        } catch (RestClientException e) {
            log.debug("Could not reach Apple artwork {} ({})", url, e.getMessage());
            return CoverProbe.unreachable();
        }
    }
}
