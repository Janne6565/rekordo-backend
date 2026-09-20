package com.rekordo.services.storage;

import com.rekordo.configuration.StorageProperties;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.AvatarCropDto;
import com.rekordo.model.core.AvatarDto;
import com.rekordo.model.exception.AvatarNotFoundException;
import com.rekordo.model.exception.PhotoTooLargeException;
import com.rekordo.model.exception.StorageQuotaExceededException;
import com.rekordo.model.exception.UnsupportedPhotoTypeException;
import com.rekordo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final long MAX_BYTES = 15_728_640;
    private static final long QUOTA_BYTES = 20_971_520;

    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;
    @Mock private StorageUsageService storageUsageService;

    private AvatarService service;

    @BeforeEach
    void setUp() {
        service = new AvatarService(
                userRepository,
                storageService,
                storageUsageService,
                new StorageProperties("http://s", "a", "b", "bucket", MAX_BYTES, MAX_BYTES, QUOTA_BYTES));
    }

    @Test
    void rendersTheFramedSquareToOneSquareJpeg() throws IOException {
        UserEntity user = user();
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));

        // A wide picture with a red band down the left third: if the crop rectangle is
        // honoured, what lands is entirely red, and if it is ignored the average is not.
        byte[] source = picture(900, 300, 300);
        service.upload(USER, file(source, "image/jpeg"), new AvatarCropDto(0, 0, 300));

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(captureStored()));
        assertThat(stored.getWidth()).isEqualTo(512);
        assertThat(stored.getHeight()).isEqualTo(512);
        assertThat(new Color(stored.getRGB(256, 256)).getRed()).isGreaterThan(200);
        assertThat(new Color(stored.getRGB(256, 256)).getBlue()).isLessThan(60);
    }

    @Test
    void framingTheOtherSideGivesTheOtherColour() throws IOException {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));

        byte[] source = picture(900, 300, 300);
        service.upload(USER, file(source, "image/jpeg"), new AvatarCropDto(600, 0, 300));

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(captureStored()));
        assertThat(new Color(stored.getRGB(256, 256)).getBlue()).isGreaterThan(200);
    }

    /**
     * The preview the person framed against was scaled to fit a 300px stage, so the
     * rectangle can miss the edge by a pixel. Refusing the upload over that would be absurd.
     */
    @Test
    void clampsARectangleThatRunsOffTheEdge() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));

        byte[] source = picture(400, 400, 400);
        service.upload(USER, file(source, "image/jpeg"), new AvatarCropDto(380, 380, 900));

        assertThat(captureStored()).isNotEmpty();
    }

    @Test
    void recordsTheUrlAndTheMomentItLanded() {
        UserEntity user = user();
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));

        AvatarDto dto = service.upload(USER, file(picture(64, 64, 64), "image/png"), new AvatarCropDto(0, 0, 64));

        assertThat(user.getAvatarKey()).isEqualTo("avatars/" + USER);
        assertThat(user.getAvatarUpdatedAt()).isNotNull();
        assertThat(dto.url()).isEqualTo("/api/v1/avatar/%s?v=%d".formatted(USER, dto.updatedAt().toEpochMilli()));
    }

    @Test
    void chargesTheAccountForTheRenderedPictureRatherThanTheOneThatArrived() {
        // What the bucket holds is the 512px JPEG, and that is what the allowance is spent
        // on. A four-megapixel original that renders to fifty kilobytes costs fifty kilobytes.
        UserEntity user = user();
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));

        service.upload(USER, file(picture(64, 64, 64), "image/png"), new AvatarCropDto(0, 0, 64));

        long stored = captureStored().length;
        assertThat(user.getAvatarBytes()).isEqualTo(stored);
        verify(storageUsageService).requireRoom(USER, stored, 0L);
    }

    @Test
    void refusesAPictureThatWouldNotFitInWhatIsLeft() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));
        org.mockito.Mockito.doThrow(new StorageQuotaExceededException(QUOTA_BYTES, QUOTA_BYTES))
                .when(storageUsageService)
                .requireRoom(eq(USER), anyLong(), anyLong());

        assertThatThrownBy(() -> service.upload(
                        USER, file(picture(64, 64, 64), "image/png"), new AvatarCropDto(0, 0, 64)))
                .isInstanceOf(StorageQuotaExceededException.class);
        verify(storageService, never()).put(any(), any(), anyLong(), any());
    }

    @Test
    void refusesAFileThatIsNotAPictureThisAppRenders() {
        assertThatThrownBy(() -> service.upload(
                        USER, file("not a picture".getBytes(), "application/pdf"), new AvatarCropDto(0, 0, 10)))
                .isInstanceOf(UnsupportedPhotoTypeException.class);
        verifyNoInteractions(storageService);
    }

    /** A JPEG content type over bytes no decoder accepts is the same answer to the person. */
    @Test
    void refusesBytesNoDecoderAccepts() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));

        assertThatThrownBy(() -> service.upload(
                        USER, file("still not a picture".getBytes(), "image/jpeg"), new AvatarCropDto(0, 0, 10)))
                .isInstanceOf(UnsupportedPhotoTypeException.class);
        verify(storageService, never()).put(any(), any(), anyLong(), any());
    }

    @Test
    void refusesAFileOverTheCeiling() {
        assertThatThrownBy(() -> service.upload(
                        USER, file(new byte[(int) MAX_BYTES + 1], "image/jpeg"), new AvatarCropDto(0, 0, 10)))
                .isInstanceOf(PhotoTooLargeException.class);
        verifyNoInteractions(storageService);
    }

    /**
     * The row is cleared before the object is deleted. The other order can leave an account
     * pointing at bytes that are gone, which shows a broken picture to every viewer.
     */
    @Test
    void removingClearsTheRowAndTheObject() {
        UserEntity user = user();
        user.setAvatarKey("avatars/" + USER);
        user.setAvatarUpdatedAt(Instant.now());
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));

        service.remove(USER);

        assertThat(user.getAvatarKey()).isNull();
        assertThat(user.getAvatarBytes()).isNull();
        assertThat(user.getAvatarUpdatedAt()).isNull();
        verify(storageService).delete("avatars/" + USER);
    }

    @Test
    void removingWhatIsNotThereIsSilent() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));

        service.remove(USER);

        verify(storageService, never()).delete(any());
    }

    @Test
    void anAccountWithNoPictureIsAFourOhFour() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(user()));

        assertThatThrownBy(() -> service.download(USER)).isInstanceOf(AvatarNotFoundException.class);
    }

    /** The url carries the moment the bytes landed, or a replacement would never be seen. */
    @Test
    void theUrlChangesWhenThePictureDoes() {
        UserEntity user = user();
        user.setAvatarKey("avatars/" + USER);
        user.setAvatarUpdatedAt(Instant.ofEpochMilli(1_000));
        String first = AvatarService.urlFor(user);
        user.setAvatarUpdatedAt(Instant.ofEpochMilli(2_000));

        assertThat(AvatarService.urlFor(user)).isNotEqualTo(first);
    }

    @Test
    void noPictureMeansNoUrlAtAll() {
        assertThat(AvatarService.urlFor(user())).isNull();
        assertThat(AvatarService.urlFor(null)).isNull();
    }

    private byte[] captureStored() {
        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(storageService).put(eq("avatars/" + USER), body.capture(), anyLong(), eq("image/jpeg"));
        try {
            return body.getValue().readAllBytes();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private UserEntity user() {
        UserEntity user = new UserEntity();
        user.setId(USER);
        user.setEmail("jonas@meyer.de");
        user.setUpdatedAt(Instant.now());
        return user;
    }

    private MockMultipartFile file(byte[] bytes, String contentType) {
        return new MockMultipartFile("file", "IMG_4127.jpg", contentType, bytes);
    }

    /** Red for the leftmost {@code band} pixels, blue for the rest. */
    private byte[] picture(int width, int height, int band) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, band, height);
        g.setColor(Color.BLUE);
        g.fillRect(band, 0, width - band, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpg", out);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }
}
