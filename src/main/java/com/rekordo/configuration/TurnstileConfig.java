package com.rekordo.configuration;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client for Cloudflare's siteverify.
 *
 * <p>Its own bean rather than a call on a shared builder, because its timeout has to be
 * short: this call sits in front of every sign-in, so a slow Cloudflare must fail fast
 * rather than hold the request open for the fifteen seconds the metadata clients allow.
 */
@Configuration
@EnableConfigurationProperties(TurnstileProperties.class)
public class TurnstileConfig {

    @Bean
    public RestClient turnstileRestClient(ObservationRegistry observations, TurnstileProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .requestFactory(factory)
                .observationRegistry(observations)
                .build();
    }
}
