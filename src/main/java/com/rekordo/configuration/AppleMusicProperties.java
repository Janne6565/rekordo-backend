package com.rekordo.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "rekordo.apple-music")
public record AppleMusicProperties(
    @NotBlank String baseUrl,
    @NotBlank String teamId,
    @NotBlank String keyId,
    String privateKey,
    @NotBlank String storefront
) {
    public boolean configured() {
        return privateKey != null && !privateKey.isBlank();
    }
}
