package com.rekordo.configuration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The three clients that talk to somebody else's API.
 *
 * <p>Each is handed the {@link ObservationRegistry} explicitly, which is what turns an
 * outbound call into an {@code http.client.requests} timer and a client span. Without it
 * these three were the least observable part of the service and the most likely to be the
 * reason it was slow: MusicBrainz is capped at one request a second and Discogs at
 * twenty-five a minute, so they are exactly where waiting happens.
 *
 * <p>Not built from an injected {@code RestClient.Builder}: Spring Boot 4 does not
 * contribute one here, and asking for it fails the context at startup rather than falling
 * back to an uninstrumented client. Registering the observation registry by hand is the
 * part that actually mattered anyway.
 */
@Configuration
@EnableConfigurationProperties({
    MusicBrainzProperties.class,
    DiscogsProperties.class,
    JwtProperties.class,
    OAuthProperties.class,
    AppleMusicProperties.class
})
public class MetadataClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private static ClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Bean
    public RestClient musicBrainzRestClient(ObservationRegistry observations, MusicBrainzProperties properties) {
        return observed(observations)
                .baseUrl(properties.baseUrl())
                .defaultHeader("User-Agent", properties.userAgent())
                .build();
    }

    /**
     * A builder with this project's timeouts, wired for observation.
     *
     * <p>A fresh builder per client: one shared instance configured in place would give the
     * last bean defined here the base URL of them all.
     *
     * <p>Nothing here names the API. Spring's client observation already tags every timer
     * with {@code client.name}, taken from the request's host, which is what tells
     * musicbrainz.org, api.discogs.com and coverartarchive.org apart on a dashboard.
     */
    private static RestClient.Builder observed(ObservationRegistry observations) {
        return RestClient.builder().requestFactory(timeouts()).observationRegistry(observations);
    }

    /**
     * Discogs rejects the default Java User-Agent outright, and serves images only to a
     * request that carries a token — so the token, when there is one, is a default header
     * rather than something every call site has to remember.
     */
    @Bean
    public RestClient discogsRestClient(ObservationRegistry observations, DiscogsProperties properties) {
        RestClient.Builder builder = observed(observations)
                .baseUrl(properties.baseUrl())
                .defaultHeader("User-Agent", properties.userAgent());
        if (properties.authenticated()) {
            builder = builder.defaultHeader("Authorization", "Discogs token=" + properties.token());
        }
        return builder.build();
    }

    @Bean
    public RestClient coverArtRestClient(ObservationRegistry observations, MusicBrainzProperties properties) {
        return observed(observations)
                .baseUrl(properties.coverArtBaseUrl())
                .defaultHeader("User-Agent", properties.userAgent())
                .build();
    }
}
