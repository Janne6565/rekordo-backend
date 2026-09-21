package com.rekordo.configuration;

import com.rekordo.services.metadata.MetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the join between the annotations and the manager.
 *
 * <p>A misspelled cache name does not fail: {@link org.springframework.cache.caffeine
 * .CaffeineCacheManager} creates unknown caches on demand, so the method would still be
 * cached, silently, under the fallback expiry rather than the one chosen for it. That is
 * the kind of mistake that shows up months later as a stale discography, so it is asserted
 * here rather than trusted.
 */
class CacheConfigTest {

    private final CacheManager manager = new CacheConfig().cacheManager();

    @Test
    @DisplayName("every cache the config names can be resolved")
    void registersTheNamedCaches() {
        List<String> names = List.of(
                CacheConfig.METADATA_SEARCH,
                CacheConfig.ARTIST_DISCOGRAPHY,
                CacheConfig.ALBUM_PRESSINGS,
                CacheConfig.BARCODE_LOOKUP,
                CacheConfig.ARTIST_SEARCH,
                CacheConfig.ALBUM_SEARCH);
        assertThat(names).allSatisfy(name -> assertThat(manager.getCache(name)).isNotNull());
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every @Cacheable on the metadata service names a configured cache")
    void annotationsMatchTheConfiguration() {
        List<String> configured = List.of(
                CacheConfig.METADATA_SEARCH,
                CacheConfig.ARTIST_DISCOGRAPHY,
                CacheConfig.ALBUM_PRESSINGS,
                CacheConfig.BARCODE_LOOKUP,
                CacheConfig.ARTIST_SEARCH,
                CacheConfig.ALBUM_SEARCH);

        List<String> annotated = Arrays.stream(MetadataService.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Cacheable.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(cacheable -> Arrays.stream(cacheable.cacheNames()))
                .distinct()
                .toList();

        assertThat(annotated)
                .as("the service is expected to cache every upstream question")
                .hasSize(6)
                .allSatisfy(name -> assertThat(configured).contains(name));
    }

    @Test
    @DisplayName("the slow upstream calls are the ones that carry a cache")
    void theSlowCallsAreCached() {
        assertThat(cacheNameOf("search")).isEqualTo(CacheConfig.METADATA_SEARCH);
        assertThat(cacheNameOf("findByBarcode")).isEqualTo(CacheConfig.BARCODE_LOOKUP);
        assertThat(cacheNameOf("albumsOfArtist")).isEqualTo(CacheConfig.ARTIST_DISCOGRAPHY);
        assertThat(cacheNameOf("releasesInGroup")).isEqualTo(CacheConfig.ALBUM_PRESSINGS);
        assertThat(cacheNameOf("searchArtists")).isEqualTo(CacheConfig.ARTIST_SEARCH);
        // Cached although Apple is not paced: this is the one answer nothing writes down,
        // so the entry is the only copy of it between requests.
        assertThat(cacheNameOf("searchAlbums")).isEqualTo(CacheConfig.ALBUM_SEARCH);
    }

    private static String cacheNameOf(String methodName) {
        Method method = Arrays.stream(MetadataService.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .filter(candidate -> candidate.getAnnotation(Cacheable.class) != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(methodName + " carries no @Cacheable"));
        return method.getAnnotation(Cacheable.class).cacheNames()[0];
    }
}
