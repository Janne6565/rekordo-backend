package com.rekordo.services.sync;

import com.rekordo.entity.WishlistItemEntity;
import com.rekordo.model.core.SyncPhotoDto;
import com.rekordo.model.core.SyncPullDto;
import com.rekordo.model.core.SyncWishDto;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a push does with a record it cannot store.
 *
 * <p>Push is one transaction, and a client only clears its pending set once the push has
 * succeeded. So a row that violates a constraint does not fail alone: it rolls back every
 * other record in the batch, and the client sends the same doomed batch again on the next
 * sync, for ever. This is the test that a bad record costs itself and nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServicePushIntakeTest {

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
        when(copyRepository.nextSyncSeq()).thenReturn(1L);
        when(wishlistItemRepository.findAllByUserIdAndIdIn(any(), anyList())).thenReturn(List.of());
        when(photoRepository.findAllByUserIdAndIdIn(any(), anyList())).thenReturn(List.of());
        when(wishlistItemRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(photoRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private SyncWishDto wish(String id) {
        return new SyncWishDto(id, "group", null, null, "T", "A", null, null, null, null, 1L, null, Map.of());
    }

    /** A picture deleted before its upload finished: no key, and never getting one. */
    private SyncPhotoDto deletedWithoutKey(String id) {
        return new SyncPhotoDto(id, null, UUID.randomUUID().toString(), null, "image/jpeg", 1L, 0, 1L, 2L, Map.of());
    }

    @Test
    void keepsATombstonedPhotoThatNeverUploaded() {
        // It has to travel: the tombstone is how the delete reaches the other devices, and
        // its key is legitimately null. This is the row that used to 500 the whole request.
        SyncPullDto result = service.push(
                USER, List.of(), List.of(), List.of(deletedWithoutKey(UUID.randomUUID().toString())), List.of(), Map.of());

        assertThat(result.photos()).hasSize(1);
        assertThat(result.photos().getFirst().storageKey()).isNull();
        verify(photoRepository).save(any());
    }

    @Test
    void dropsALivePhotoWithNoBytesBehindIt() {
        // No key and not deleted names bytes that are nowhere: no other device could ever
        // fetch it, so storing it would only hand everyone a broken image.
        SyncPhotoDto live =
                new SyncPhotoDto(UUID.randomUUID().toString(), null, null, null, "image/jpeg", 1L, 0, 1L, null, Map.of());

        SyncPullDto result = service.push(USER, List.of(), List.of(), List.of(live), List.of(), Map.of());

        assertThat(result.photos()).isEmpty();
        verify(photoRepository, never()).save(any());
    }

    @Test
    void dropsOnlyTheBadRecordAndStoresTheRestOfTheBatch() {
        // The whole point. One unusable row used to take a device's entire collection with
        // it, over and over, because the client cannot tell a rejection from an outage.
        SyncWishDto good = wish(UUID.randomUUID().toString());
        SyncWishDto noCreatedAt =
                new SyncWishDto(
                        UUID.randomUUID().toString(), "group", null, null, "T", "A", null, null, null, null, null, null, Map.of());
        SyncWishDto notAUuid = wish("not-a-uuid");

        SyncPullDto result =
                service.push(USER, List.of(), List.of(good, noCreatedAt, notAUuid), List.of(), List.of(), Map.of());

        assertThat(result.wishes()).extracting(SyncWishDto::id).containsExactly(good.id());
        verify(wishlistItemRepository).save(any(WishlistItemEntity.class));
    }
}
