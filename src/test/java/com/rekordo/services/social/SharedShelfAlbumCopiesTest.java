package com.rekordo.services.social;

import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.model.core.SharedCopyDto;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import com.rekordo.repository.ReleaseRepository;
import com.rekordo.repository.UserRepository;
import com.rekordo.repository.WishlistItemRepository;
import com.rekordo.services.metadata.MetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A friend's shelf, for copies whose owner never chose a pressing.
 *
 * <p>Such a copy names an album, which the mirror holds as a group row, not a release. The
 * shelf looked only at releases, so unless the owner's device had handed the album over as
 * a release row, the copy showed as "Untitled" by "Unknown artist" with no sleeve -- every
 * record picked from the Apple album search on the web, for one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedShelfAlbumCopiesTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final String APPLE_ALBUM = "applemusic:590434066";

    @Mock private UserRepository userRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private WishlistItemRepository wishlistItemRepository;
    @Mock private PhotoRepository photoRepository;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private MetadataService metadataService;
    @Mock private FriendshipService friendshipService;
    @Mock private VisibilityService visibilityService;

    @InjectMocks private ProfileService service;

    private CopyEntity albumCopy() {
        UserEntity owner = new UserEntity();
        owner.setId(OWNER);
        owner.setHandle("anna");
        when(userRepository.findByHandleIgnoreCase("anna")).thenReturn(Optional.of(owner));
        when(visibilityService.canSeeCollection(VIEWER, OWNER)).thenReturn(true);

        CopyEntity copy = new CopyEntity();
        copy.setId(UUID.randomUUID());
        copy.setUserId(OWNER);
        copy.setAlbumId(APPLE_ALBUM);
        copy.setReleaseId(null);
        copy.setCreatedAt(Instant.now().toEpochMilli());
        when(copyRepository.findVisible(eq(OWNER), any())).thenReturn(List.of(copy));
        when(copyRepository.countVisible(OWNER)).thenReturn(1L);
        when(releaseRepository.findAllByExternalIdIn(any())).thenReturn(List.of());
        when(photoRepository.findVisibleForCopies(any(), any())).thenReturn(List.of());
        return copy;
    }

    @Test
    void drawsACopyWithNoPressingFromItsAlbum() {
        albumCopy();
        when(metadataService.describeAlbums(List.of(APPLE_ALBUM))).thenReturn(Map.of(APPLE_ALBUM, new ReleaseDto(
                APPLE_ALBUM, APPLE_ALBUM, "A Thousand Suns", "Linkin Park", 2010, Format.OTHER,
                null, null, null, null, null, null, null,
                "https://is1.mzstatic.com/a/600x600bb.jpg", null)));

        SharedCopyDto shown = service.collection(VIEWER, "anna").copies().getFirst();

        assertThat(shown.title()).isEqualTo("A Thousand Suns");
        assertThat(shown.artistName()).isEqualTo("Linkin Park");
        assertThat(shown.year()).isEqualTo(2010);
        assertThat(shown.coverArtUrl()).isEqualTo("https://is1.mzstatic.com/a/600x600bb.jpg");
    }

    @Test
    void asksNothingMoreWhenEveryCopyNamesAPressing() {
        CopyEntity copy = albumCopy();
        copy.setReleaseId("local:" + copy.getId());

        service.collection(VIEWER, "anna");

        verify(metadataService, never()).describeAlbums(any());
    }
}
