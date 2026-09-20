package com.rekordo.services.sync;

import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.PhotoEntity;
import com.rekordo.entity.WishlistItemEntity;
import com.rekordo.model.core.SyncPullDto;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import com.rekordo.repository.WishlistItemRepository;
import com.rekordo.services.metadata.MetadataService;
import com.rekordo.services.social.ActivityService;
import com.rekordo.services.storage.StorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The pull cursor has to satisfy one invariant: it must never advance past a record the
 * response did not include. Getting that wrong strands records on the server with the
 * client believing it is up to date.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceCursorTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock private CopyRepository copyRepository;
    @Mock private WishlistItemRepository wishlistItemRepository;
    @Mock private PhotoRepository photoRepository;
    @Mock private StorageService storageService;
    @Mock private ActivityService activityService;
    @Mock private MetadataService metadataService;

    private SyncService service;

    @BeforeEach
    void setUp() {
        service = new SyncService(
                copyRepository,
                wishlistItemRepository,
                photoRepository,
                storageService,
                activityService,
                metadataService,
                new ObjectMapper(),
                new SimpleMeterRegistry());
    }

    private CopyEntity copy(long seq) {
        CopyEntity entity = new CopyEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(USER);
        entity.setReleaseId("rel");
        entity.setCurrency("EUR");
        entity.setCreatedAt(1L);
        entity.setFieldClocks("{}");
        entity.setSyncSeq(seq);
        return entity;
    }

    private WishlistItemEntity wish(long seq) {
        WishlistItemEntity entity = new WishlistItemEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(USER);
        entity.setAlbumId("group");
        entity.setTitle("T");
        entity.setArtistName("A");
        entity.setCreatedAt(1L);
        entity.setFieldClocks("{}");
        entity.setSyncSeq(seq);
        return entity;
    }

    private PhotoEntity photo(long seq) {
        PhotoEntity entity = new PhotoEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(USER);
        entity.setCopyId(UUID.randomUUID());
        entity.setStorageKey("k");
        entity.setContentType("image/jpeg");
        entity.setByteSize(1L);
        entity.setSortIndex(0);
        entity.setCreatedAt(1L);
        entity.setFieldClocks("{}");
        entity.setSyncSeq(seq);
        return entity;
    }

    private void given(List<CopyEntity> copies, List<WishlistItemEntity> wishes) {
        given(copies, wishes, List.of());
    }

    private void given(List<CopyEntity> copies, List<WishlistItemEntity> wishes, List<PhotoEntity> photos) {
        when(copyRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(any(), anyLong()))
                .thenReturn(copies);
        when(wishlistItemRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(any(), anyLong()))
                .thenReturn(wishes);
        when(photoRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(any(), anyLong()))
                .thenReturn(photos);
    }

    @Test
    void sendsBothKindsWhenEverythingFits() {
        // The regression: clamping the cursor to the lower kind withheld the copy at seq 9
        // while reporting hasMore=false, so the client stopped and never asked again.
        given(List.of(copy(9)), List.of(wish(4)));

        SyncPullDto page = service.pull(USER, 0);

        assertThat(page.copies()).hasSize(1);
        assertThat(page.wishes()).hasSize(1);
        assertThat(page.cursor()).isEqualTo(9);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void sendsBothKindsWhenTheWishIsTheLaterOne() {
        given(List.of(copy(3)), List.of(wish(11)));

        SyncPullDto page = service.pull(USER, 0);

        assertThat(page.copies()).hasSize(1);
        assertThat(page.wishes()).hasSize(1);
        assertThat(page.cursor()).isEqualTo(11);
    }

    @Test
    void keepsTheCursorWhereItWasWhenNothingChanged() {
        given(List.of(), List.of());

        SyncPullDto page = service.pull(USER, 42);

        assertThat(page.copies()).isEmpty();
        assertThat(page.wishes()).isEmpty();
        assertThat(page.cursor()).isEqualTo(42);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void sendsAllThreeKindsTogether() {
        given(List.of(copy(3)), List.of(wish(11)), List.of(photo(7)));

        SyncPullDto page = service.pull(USER, 0);

        assertThat(page.copies()).hasSize(1);
        assertThat(page.wishes()).hasSize(1);
        assertThat(page.photos()).hasSize(1);
        assertThat(page.cursor()).isEqualTo(11);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void holdsTheCursorBackAndAsksForAnotherPageWhenOneKindIsTruncated() {
        List<CopyEntity> manyCopies = new java.util.ArrayList<>();
        for (long seq = 1; seq <= 600; seq++) {
            manyCopies.add(copy(seq));
        }
        given(manyCopies, List.of(wish(900)), List.of(photo(950)));

        SyncPullDto page = service.pull(USER, 0);

        // The cursor stops at the last copy actually sent, and the far-later wish is held
        // back rather than being skipped over.
        assertThat(page.cursor()).isEqualTo(500);
        assertThat(page.copies()).hasSize(500);
        assertThat(page.wishes()).isEmpty();
        assertThat(page.photos()).isEmpty();
        assertThat(page.hasMore()).isTrue();
    }
}
