package com.rekordo.services.metadata;

import com.rekordo.client.CoverArtClient;
import com.rekordo.client.DiscogsClient;
import com.rekordo.client.MusicBrainzClient;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.repository.ArtistImageRepository;
import com.rekordo.repository.ReleaseGroupRepository;
import com.rekordo.repository.ReleaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The mirror learning from the device that made the copy.
 *
 * <p>Sync moves copies and never the catalogue, and the mirror only ever heard about a
 * release when somebody looked it up through this proxy. A collection that arrived any
 * other way — an archive imported into a fresh deployment — therefore named releases the
 * server could not resolve, leaving every other device with a shelf it had no way to fill.
 * For a Discogs id that was permanent: nothing fetches those by id at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdoptFromClientTest {

    private static final String DISCOGS_RELEASE = "discogs:28993519";
    private static final String DISCOGS_ALBUM = "discogs:3124758";

    @Mock private MusicBrainzClient musicBrainzClient;
    @Mock private DiscogsClient discogsClient;
    @Mock private CoverArtClient coverArtClient;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private ReleaseGroupRepository releaseGroupRepository;
    @Mock private ArtistImageRepository artistImageRepository;

    @InjectMocks private MetadataService service;

    private static ReleaseDto dto(String id) {
        return new ReleaseDto(
                id,
                DISCOGS_ALBUM,
                "Spider-Man: Across The Spider-Verse",
                "Metro Boomin",
                2023,
                Format.VINYL,
                "Boominati Worldwide",
                "602458370157",
                "US",
                "602458370157",
                null,
                null,
                null,
                "https://covers.example/spider.jpg",
                null);
    }

    private void mirrorHolds(ReleaseEntity... held) {
        when(releaseRepository.findAllByExternalIdIn(anyCollection())).thenReturn(List.of(held));
    }

    @Test
    void takesInAReleaseTheMirrorHasNeverSeen() {
        mirrorHolds();
        when(releaseGroupRepository.findByExternalId(DISCOGS_ALBUM)).thenReturn(Optional.empty());
        when(releaseGroupRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        int adopted = service.adoptFromClient(List.of(dto(DISCOGS_RELEASE)));

        assertThat(adopted).isEqualTo(1);
        ArgumentCaptor<ReleaseEntity> saved = ArgumentCaptor.forClass(ReleaseEntity.class);
        verify(releaseRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalId()).isEqualTo(DISCOGS_RELEASE);
        assertThat(saved.getValue().getTitle()).isEqualTo("Spider-Man: Across The Spider-Verse");
        assertThat(saved.getValue().getFormat()).isEqualTo(Format.VINYL);
        // Unknown rather than inferred: the client's URL may be one this deployment cannot
        // serve, and null is what makes a later lookup actually probe it.
        assertThat(saved.getValue().getHasCoverArt()).isNull();
    }

    @Test
    void leavesAReleaseTheMirrorAlreadyFetchedForItself() {
        // What the mirror fetched carries a sampled palette and a probed cover. A client
        // echo is strictly worse, and this must never be a way for one account to rewrite
        // what every account reads.
        ReleaseEntity held = new ReleaseEntity();
        held.setId(UUID.randomUUID());
        held.setExternalId(DISCOGS_RELEASE);
        held.setTitle("The mirror's own title");
        mirrorHolds(held);

        int adopted = service.adoptFromClient(List.of(dto(DISCOGS_RELEASE)));

        assertThat(adopted).isZero();
        verify(releaseRepository, never()).save(any());
    }

    @Test
    void ignoresAHandEnteredRelease() {
        // "local:<copy id>" is derived from the copy itself and belongs in nobody's cache.
        int adopted = service.adoptFromClient(List.of(dto("local:" + UUID.randomUUID())));

        assertThat(adopted).isZero();
        verify(releaseRepository, never()).save(any());
    }

    @Test
    void ignoresARowWithNothingToDrawAShelfWith() {
        mirrorHolds();
        ReleaseDto untitled = new ReleaseDto(
                DISCOGS_RELEASE, DISCOGS_ALBUM, "  ", "Metro Boomin", null, Format.VINYL,
                null, null, null, null, null, null, null, null, null);

        assertThat(service.adoptFromClient(List.of(untitled))).isZero();
        verify(releaseRepository, never()).save(any());
    }

    @Test
    void takesNothingFromAnEmptyOrAbsentList() {
        assertThat(service.adoptFromClient(List.of())).isZero();
        assertThat(service.adoptFromClient(null)).isZero();
        verify(releaseRepository, never()).save(any());
    }
}
