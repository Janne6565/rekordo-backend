package com.rekordo.services.storage;

import com.rekordo.configuration.StorageProperties;
import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.PhotoEntity;
import com.rekordo.model.core.PhotoUploadDto;
import com.rekordo.model.exception.PhotoNotFoundException;
import com.rekordo.model.exception.PhotoOwnerRequiredException;
import com.rekordo.model.exception.PhotoTooLargeException;
import com.rekordo.model.exception.StorageQuotaExceededException;
import com.rekordo.model.exception.UnsupportedPhotoTypeException;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import com.rekordo.services.social.VisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhotoServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID PHOTO = UUID.randomUUID();
    private static final UUID COPY = UUID.randomUUID();
    private static final UUID WISH = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final long MAX_BYTES = 1_000_000;
    private static final long QUOTA_BYTES = 20_971_520;

    @Mock private PhotoRepository photoRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private StorageService storageService;
    @Mock private StorageUsageService storageUsageService;
    @Mock private VisibilityService visibilityService;

    private PhotoService service;

    @BeforeEach
    void setUp() {
        service = new PhotoService(
                photoRepository,
                copyRepository,
                storageService,
                storageUsageService,
                new StorageProperties("http://localhost:9000", "a", "b", "bucket", MAX_BYTES, MAX_BYTES, QUOTA_BYTES),
                visibilityService);
    }

    private MockMultipartFile file(String contentType, int bytes) {
        return new MockMultipartFile("file", "sleeve.jpg", contentType, new byte[bytes]);
    }

    @Test
    void storesTheBytesUnderAKeyNamespacedByUser() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.empty());
        when(photoRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        PhotoUploadDto result = service.upload(USER, PHOTO, COPY, null, file("image/jpeg", 128));

        // Namespaced so one account's objects can never be confused with another's.
        assertThat(result.storageKey()).isEqualTo(USER + "/" + PHOTO);
        assertThat(result.byteSize()).isEqualTo(128);
    }

    @Test
    void rejectsAnythingThatIsNotAnImageItStores() {
        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, null, file("application/pdf", 10)))
                .isInstanceOf(UnsupportedPhotoTypeException.class);
        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, null, file(null, 10)))
                .isInstanceOf(UnsupportedPhotoTypeException.class);

        // Nothing reaches storage: the check runs before the write, not after it.
        verify(storageService, never()).put(any(), any(), anyLong(), any());
    }

    @Test
    void rejectsAnOversizedUploadBeforeStoringIt() {
        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, null, file("image/jpeg", (int) MAX_BYTES + 1)))
                .isInstanceOf(PhotoTooLargeException.class);

        verify(storageService, never()).put(any(), any(), anyLong(), any());
    }

    @Test
    void refusesAnUploadThatWouldTakeTheAccountPastItsAllowance() {
        // Refused before the object is written, like every other refusal here: an upload the
        // bucket accepted and no row references is storage nothing will ever reclaim.
        org.mockito.Mockito.doThrow(new StorageQuotaExceededException(QUOTA_BYTES, QUOTA_BYTES))
                .when(storageUsageService)
                .requireRoom(eq(USER), anyLong(), anyLong());

        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, null, file("image/jpeg", 128)))
                .isInstanceOf(StorageQuotaExceededException.class);

        verify(storageService, never()).put(any(), any(), anyLong(), any());
        verify(photoRepository, never()).save(any());
    }

    @Test
    void chargesOnlyTheDifferenceWhenAPhotoIsUploadedOverItself() {
        // The key is derived from the id, so a second upload overwrites the first rather
        // than adding to it. Charging for both would refuse a replacement that frees space.
        PhotoEntity existing = photoOf(USER);
        existing.setByteSize(900L);
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(existing));
        when(photoRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.upload(USER, PHOTO, COPY, null, file("image/jpeg", 128));

        verify(storageUsageService).requireRoom(USER, 128L, 900L);
    }

    @Test
    void acceptsTheFormatsPhoneCamerasProduce() {
        when(photoRepository.findById(any())).thenReturn(Optional.empty());
        when(photoRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        for (String type : new String[] {"image/jpeg", "image/png", "image/webp", "image/heic"}) {
            assertThat(service.upload(USER, UUID.randomUUID(), COPY, null, file(type, 16)).contentType())
                    .isEqualTo(type);
        }
    }

    @Test
    void refusesToServeAPhotoBelongingToSomebodyElseWhoSharesNothing() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(STRANGER)));
        when(visibilityService.canSeeCollection(USER, STRANGER)).thenReturn(false);

        assertThatThrownBy(() -> service.download(USER, PHOTO)).isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void refusesToServeADeletedPhoto() {
        PhotoEntity deleted = photoOf(USER);
        deleted.setDeletedAt(9000L);
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.download(USER, PHOTO)).isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void servesAFriendsPhotoWhenTheirCollectionIsOpenToTheViewer() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(STRANGER)));
        when(visibilityService.canSeeCollection(USER, STRANGER)).thenReturn(true);
        when(copyRepository.findById(COPY)).thenReturn(Optional.of(copyOf(STRANGER, false)));
        when(storageService.get(any())).thenReturn(null);

        assertThatCode(() -> service.download(USER, PHOTO)).doesNotThrowAnyException();
    }

    @Test
    void servesAPublicShelfToSomebodyWithNoAccountAtAll() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(STRANGER)));
        when(visibilityService.canSeeCollection(null, STRANGER)).thenReturn(true);
        when(copyRepository.findById(COPY)).thenReturn(Optional.of(copyOf(STRANGER, false)));
        when(storageService.get(any())).thenReturn(null);

        assertThatCode(() -> service.download(null, PHOTO)).doesNotThrowAnyException();
    }

    @Test
    void withholdsThePhotoOfACopyHiddenOneByOne() {
        // The shelf is open, but this record is not. Otherwise hiding a copy would leave its
        // sleeve reachable by anyone who had the URL.
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(STRANGER)));
        when(visibilityService.canSeeCollection(USER, STRANGER)).thenReturn(true);
        when(copyRepository.findById(COPY)).thenReturn(Optional.of(copyOf(STRANGER, true)));

        assertThatThrownBy(() -> service.download(USER, PHOTO)).isInstanceOf(PhotoNotFoundException.class);
    }

    @Test
    void alwaysServesTheOwnerTheirOwnPhotoWithoutConsultingAnySetting() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(USER)));
        when(storageService.get(any())).thenReturn(null);

        assertThatCode(() -> service.download(USER, PHOTO)).doesNotThrowAnyException();
    }

    @Test
    void storesAPictureThatBelongsToAWishlistEntryInstead() {
        // The record no catalogue has: nothing can ever hand this entry artwork, so the
        // only cover it will ever have is this upload.
        PhotoUploadDto result = service.upload(USER, PHOTO, null, WISH, file("image/jpeg", 128));

        ArgumentCaptor<PhotoEntity> saved = ArgumentCaptor.forClass(PhotoEntity.class);
        verify(photoRepository).save(saved.capture());
        assertThat(saved.getValue().getWishId()).isEqualTo(WISH);
        assertThat(saved.getValue().getCopyId()).isNull();
        // The key never mentioned the owner, which is why re-parenting one costs nothing.
        assertThat(result.storageKey()).isEqualTo(USER + "/" + PHOTO);
    }

    @Test
    void refusesAnUploadThatNamesNoOwnerOrTwo() {
        // Checked before a byte is stored: the object goes to MinIO first, and an upload
        // no record can reference is an object nothing will ever clean up.
        assertThatThrownBy(() -> service.upload(USER, PHOTO, null, null, file("image/jpeg", 8)))
                .isInstanceOf(PhotoOwnerRequiredException.class);
        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, WISH, file("image/jpeg", 8)))
                .isInstanceOf(PhotoOwnerRequiredException.class);
        verifyNoInteractions(storageService);
    }

    @Test
    void keepsAWishesPictureToItsOwnerEvenWhenTheShelfIsOpen() {
        // A wishlist entry is not a record on a shelf, and it has no visibility of its own
        // to consult — so there is no answer here that lets a friend through.
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(wishPhotoOf(STRANGER)));
        when(visibilityService.canSeeCollection(USER, STRANGER)).thenReturn(true);

        assertThatThrownBy(() -> service.download(USER, PHOTO)).isInstanceOf(PhotoNotFoundException.class);
        verifyNoInteractions(copyRepository);
    }

    @Test
    void stillServesAWishesPictureToTheOwner() {
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(wishPhotoOf(USER)));
        when(storageService.get(any())).thenReturn(null);

        assertThatCode(() -> service.download(USER, PHOTO)).doesNotThrowAnyException();
    }

    private static PhotoEntity wishPhotoOf(UUID owner) {
        PhotoEntity photo = photoOf(owner);
        photo.setCopyId(null);
        photo.setWishId(WISH);
        return photo;
    }

    @Test
    void refusesAnUploadAgainstAPhotoThatBelongsToSomebodyElse() {
        // The id is the client's, and a photo id is readable off any shelf the caller can
        // see. Without this, uploading against one rewrites that row's owner and key: the
        // picture leaves its owner's collection and lands in the caller's.
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photoOf(STRANGER)));

        assertThatThrownBy(() -> service.upload(USER, PHOTO, COPY, null, file("image/jpeg", 100)))
                .isInstanceOf(PhotoNotFoundException.class);

        // Nothing written, and nothing said about whether the id was real.
        verifyNoInteractions(storageService);
        verify(photoRepository, never()).save(any());
    }

    @Test
    void servesAContentTypeTheAllowlistHolds() {
        // The bytes come back from the same origin as the web app, so a stored "text/html"
        // would be script running as the app itself. The stored value is not trusted at the
        // point it is put in a header.
        PhotoEntity photo = photoOf(USER);
        photo.setContentType("text/html");
        when(photoRepository.findById(PHOTO)).thenReturn(Optional.of(photo));

        assertThat(service.download(USER, PHOTO).contentType()).isEqualTo("application/octet-stream");
    }

    private static PhotoEntity photoOf(UUID owner) {
        PhotoEntity photo = new PhotoEntity();
        photo.setId(PHOTO);
        photo.setUserId(owner);
        photo.setCopyId(COPY);
        photo.setStorageKey(owner + "/" + PHOTO);
        photo.setContentType("image/jpeg");
        photo.setByteSize(128L);
        return photo;
    }

    private static CopyEntity copyOf(UUID owner, boolean hidden) {
        CopyEntity copy = new CopyEntity();
        copy.setId(COPY);
        copy.setUserId(owner);
        copy.setHidden(hidden);
        return copy;
    }
}
