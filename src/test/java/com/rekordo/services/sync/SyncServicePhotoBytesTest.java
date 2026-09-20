package com.rekordo.services.sync;

import com.rekordo.entity.PhotoEntity;
import com.rekordo.model.core.SyncPhotoDto;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who decides where a photo's bytes are.
 *
 * <p>Not the client. A pushed record used to carry its own {@code storageKey}, and the key is
 * what the download endpoint hands to storage — so a row naming somebody else's object was
 * served that object, to a caller the visibility rules never had to be asked about, because
 * the row itself was theirs. A tombstone naming it deleted it. These are the tests that the
 * server answers for all three fields that name bytes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncServicePhotoBytesTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();
    private static final UUID PHOTO = UUID.randomUUID();
    private static final String SOMEBODY_ELSES_OBJECT = VICTIM + "/" + UUID.randomUUID();

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
        when(photoRepository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private SyncPhotoDto photo(String storageKey, String contentType, Long deletedAt) {
        return new SyncPhotoDto(
                PHOTO.toString(),
                UUID.randomUUID().toString(),
                null,
                storageKey,
                contentType,
                4L,
                0,
                1L,
                deletedAt,
                Map.of());
    }

    private SyncPullDto push(SyncPhotoDto photo) {
        return service.push(USER, List.of(), List.of(), List.of(photo), List.of(), Map.of());
    }

    @Test
    void aPushedKeyIsReplacedByTheOneTheIdsDerive() {
        SyncPullDto result = push(photo(SOMEBODY_ELSES_OBJECT, "image/jpeg", null));

        assertThat(result.photos()).singleElement().satisfies(stored -> assertThat(stored.storageKey())
                .isEqualTo(USER + "/" + PHOTO));
    }

    @Test
    void aTombstoneCannotDeleteSomebodyElsesObject() {
        push(photo(SOMEBODY_ELSES_OBJECT, "image/jpeg", 2L));

        // The delete still happens — a deleted photo's bytes are nobody's — but only ever
        // against the caller's own object.
        verify(storageService).delete(USER + "/" + PHOTO);
        verify(storageService, never()).delete(SOMEBODY_ELSES_OBJECT);
    }

    @Test
    void aTombstoneThatNeverUploadedStillDeletesNothing() {
        // Its key is legitimately null, and deriving one here would ask storage for an object
        // that was never written.
        push(photo(null, "image/jpeg", 2L));

        verify(storageService, never()).delete(anyString());
    }

    @Test
    void aPushedContentTypeOffTheAllowlistIsNotStored() {
        // The download endpoint puts this in a header, on the same origin as the web app.
        SyncPullDto result = push(photo("whatever", "text/html", null));

        assertThat(result.photos()).singleElement().satisfies(stored -> assertThat(stored.contentType())
                .isEqualTo("application/octet-stream"));
    }

    @Test
    void aRowTheServerAlreadyHoldsKeepsWhatTheUploadWrote() {
        // The upload endpoint is the only thing that has seen the bytes. Once it has spoken,
        // no push may talk the row into naming anything else — whatever field clock it
        // carries, since a clock is the client's to choose too.
        PhotoEntity uploaded = new PhotoEntity();
        uploaded.setId(PHOTO);
        uploaded.setUserId(USER);
        uploaded.setStorageKey(USER + "/" + PHOTO);
        uploaded.setContentType("image/png");
        uploaded.setByteSize(4096L);
        uploaded.setCreatedAt(1L);
        uploaded.setSortIndex(0);
        uploaded.setFieldClocks("{}");
        uploaded.setSyncSeq(1L);
        when(photoRepository.findAllByUserIdAndIdIn(any(), anyList())).thenReturn(List.of(uploaded));

        SyncPullDto result = push(new SyncPhotoDto(
                PHOTO.toString(),
                null,
                null,
                SOMEBODY_ELSES_OBJECT,
                "text/html",
                1L,
                0,
                1L,
                null,
                Map.of("storageKey", "z-newer-than-anything", "contentType", "z-newer-than-anything")));

        assertThat(result.photos()).singleElement().satisfies(stored -> {
            assertThat(stored.storageKey()).isEqualTo(USER + "/" + PHOTO);
            assertThat(stored.contentType()).isEqualTo("image/png");
            assertThat(stored.byteSize()).isEqualTo(4096L);
        });
    }

    @Test
    void aPushedByteSizeIsNotTakenAtItsWord() {
        // The size is what the 20 MB allowance is summed from, and only the upload endpoint
        // has ever measured it. Zero until it does.
        SyncPullDto result = push(photo(USER + "/" + PHOTO, "image/jpeg", null));

        assertThat(result.photos()).singleElement().satisfies(stored -> assertThat(stored.byteSize())
                .isZero());
    }

    @Test
    void aNegativeByteSizeCannotBeStored() {
        // The bypass this guards: sum(byte_size) going negative makes StorageUsageService
        // read the account as owing nothing, and the ceiling stops applying to it entirely.
        SyncPullDto result = push(new SyncPhotoDto(
                PHOTO.toString(),
                UUID.randomUUID().toString(),
                null,
                USER + "/" + PHOTO,
                "image/jpeg",
                -2_000_000_000L,
                0,
                1L,
                null,
                Map.of()));

        assertThat(result.photos()).singleElement().satisfies(stored -> assertThat(stored.byteSize())
                .isZero());
        verify(photoRepository).save(argThat(saved -> saved.getByteSize() >= 0L));
    }

    @Test
    void anOrdinaryPushIsUntouched() {
        // The correction has to be invisible to a client that was telling the truth, which is
        // every client this project ships.
        String honest = USER + "/" + PHOTO;
        SyncPullDto result = push(photo(honest, "image/jpeg", null));

        assertThat(result.photos()).singleElement().satisfies(stored -> {
            assertThat(stored.storageKey()).isEqualTo(honest);
            assertThat(stored.contentType()).isEqualTo("image/jpeg");
        });
        assertThat(Optional.ofNullable(result.photos().getFirst().deletedAt())).isEmpty();
    }
}
