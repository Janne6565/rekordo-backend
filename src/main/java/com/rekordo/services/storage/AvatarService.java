package com.rekordo.services.storage;

import com.rekordo.configuration.StorageProperties;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.AvatarCropDto;
import com.rekordo.model.core.AvatarDto;
import com.rekordo.model.exception.AvatarNotFoundException;
import com.rekordo.model.exception.PhotoTooLargeException;
import com.rekordo.model.exception.UnsupportedPhotoTypeException;
import com.rekordo.repository.UserRepository;
import io.minio.GetObjectResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * The profile picture (turn 27) — one per account, public wherever the handle resolves.
 *
 * <p>Deliberately not a {@code PhotoEntity}. A sleeve photo is collection data: the client
 * owns its id, it merges through sync, it is hidden with the copy it pictures, and
 * {@code PhotoService} asks {@code VisibilityService} about every single byte. A profile
 * picture is none of that. It belongs to the account rather than the shelf, there is
 * exactly one, and it is public even for a collector whose shelf is closed — which is
 * precisely why 27b says so out loud before the upload happens.
 *
 * <p>What arrives is the original picture plus the square the user framed; what is stored
 * is one rendered {@value #RENDER_PX}px JPEG. Rendering here rather than on the device is
 * what makes every client produce the same circle from the same choice, and re-encoding
 * has a second effect worth having on a picture this public: the EXIF block, which on a
 * phone photo carries the place it was taken, does not survive it.
 */
@Service
@RequiredArgsConstructor
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    /**
     * What the server decodes. Narrower than the list 27d names to the user, on purpose:
     * both clients decode the chosen file themselves to draw the framing step, and upload
     * what came out of that — upright, unrotated, and without the EXIF orientation flag
     * that {@link ImageIO} would ignore and every phone gallery applies. So HEIC and WebP
     * are things a person may choose (and the device converts) rather than things that
     * reach this method.
     */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png");

    /**
     * One rendered size, not a ladder. The largest circle the app draws is 56 (27f, 27i),
     * so 512 covers it at any pixel density with room to spare, and a single object means
     * a single URL — one cache entry per person rather than six, on a picture that appears
     * beside itself at four different sizes on one screen.
     */
    private static final int RENDER_PX = 512;

    /**
     * A guard against a small file that decodes enormous. 15 MB of PNG can be a hundred
     * megapixels, and a hundred megapixels is 400 MB of {@code int[]} before anything has
     * been cropped. Checked from the header, before a pixel is decoded.
     */
    private static final long MAX_PIXELS = 50_000_000L;

    private final UserRepository userRepository;
    private final StorageService storageService;
    private final StorageUsageService storageUsageService;
    private final StorageProperties properties;

    /**
     * Where a picture is served from, or null for the overwhelming majority who have none.
     *
     * <p>The timestamp is in the query rather than the key: replacing a picture writes over
     * the same object, so without it a viewer holding yesterday's bytes would keep them
     * for as long as the cache header allows.
     */
    public static String urlFor(UserEntity user) {
        if (user == null || user.getAvatarKey() == null || user.getAvatarUpdatedAt() == null) {
            return null;
        }
        return "/api/v1/avatar/%s?v=%d".formatted(user.getId(), user.getAvatarUpdatedAt().toEpochMilli());
    }

    /** Renders the framed square and puts it on the account, replacing whatever was there. */
    @Transactional
    public AvatarDto upload(UUID userId, MultipartFile file, AvatarCropDto crop) {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new UnsupportedPhotoTypeException(String.valueOf(contentType));
        }
        if (file.getSize() > properties.maxUploadBytes()) {
            throw new PhotoTooLargeException(properties.maxUploadBytes());
        }
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new AvatarNotFoundException(userId));

        byte[] rendered;
        try {
            byte[] source = file.getBytes();
            guardDimensions(source);
            rendered = render(source, crop);
        } catch (IOException e) {
            // A file the decoder cannot read is the same answer to the person as a file
            // type we never accepted: it is not a picture this app can use.
            throw new UnsupportedPhotoTypeException(String.valueOf(contentType));
        }

        // Checked against the rendered bytes rather than the upload, and therefore after
        // rendering rather than before: what the account keeps is the 512px JPEG, and
        // charging it for the four megapixels that produced one would refuse a picture that
        // costs fifty kilobytes. Replacing a picture writes over the same key, so what the
        // one already there weighs is not spent twice.
        storageUsageService.requireRoom(
                userId, rendered.length, user.getAvatarKey() == null || user.getAvatarBytes() == null
                        ? 0L
                        : user.getAvatarBytes());

        String key = "avatars/%s".formatted(userId);
        storageService.put(key, new ByteArrayInputStream(rendered), rendered.length, "image/jpeg");

        Instant now = Instant.now();
        user.setAvatarKey(key);
        user.setAvatarBytes((long) rendered.length);
        user.setAvatarUpdatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);

        log.debug("Stored profile picture for {} ({} bytes rendered from {})", userId, rendered.length, file.getSize());
        return new AvatarDto(urlFor(user), now);
    }

    /**
     * Back to the initials circle (27e).
     *
     * <p>The row is cleared first and the object deleted after. The other order would leave
     * an account pointing at bytes that are gone if the delete succeeded and the write did
     * not — an orphaned object costs a few kilobytes, an orphaned row shows a broken
     * picture to everyone who opens the profile.
     */
    @Transactional
    public void remove(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new AvatarNotFoundException(userId));
        String key = user.getAvatarKey();
        if (key == null) {
            return;
        }
        user.setAvatarKey(null);
        user.setAvatarBytes(null);
        user.setAvatarUpdatedAt(null);
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);
        storageService.delete(key);
    }

    /**
     * The bytes, for anybody at all.
     *
     * <p>No {@code VisibilityService} call, and that is the decision of the turn rather than
     * an omission: the picture is account data, so it renders above a collection that will
     * not (27f). An account with no picture is a 404, the same answer a handle nobody holds
     * gets — there is nothing here to confirm or deny.
     */
    @Transactional(readOnly = true)
    public Download download(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new AvatarNotFoundException(userId));
        if (user.getAvatarKey() == null) {
            throw new AvatarNotFoundException(userId);
        }
        return new Download(storageService.get(user.getAvatarKey()), user.getAvatarUpdatedAt());
    }

    /** Reads the header alone to find out how much memory decoding would ask for. */
    private void guardDimensions(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) {
                throw new IOException("no image input stream");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("no reader for this image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    throw new PhotoTooLargeException(properties.maxUploadBytes());
                }
            } finally {
                reader.dispose();
            }
        }
    }

    /** Crops to the framed square and draws it down to one {@value #RENDER_PX}px JPEG. */
    private byte[] render(byte[] source, AvatarCropDto crop) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("no decoder accepted the image");
        }

        // Clamped rather than refused: the preview the person framed against was scaled to
        // fit a 300px stage, so the rectangle that comes back can miss the edge by a pixel
        // or two. Refusing an upload over that would be absurd, and silently taking the
        // nearest square that does fit is what they meant.
        int size = Math.max(1, Math.min(crop.size(), Math.min(image.getWidth(), image.getHeight())));
        int x = Math.max(0, Math.min(crop.x(), image.getWidth() - size));
        int y = Math.max(0, Math.min(crop.y(), image.getHeight() - size));
        BufferedImage square = image.getSubimage(x, y, size, size);

        // Halved repeatedly before the last draw. One bilinear step from 4032px to 512px
        // samples eight source pixels in sixty-four and reads as noise; halving costs a few
        // milliseconds and is the difference between a face and a mess at 24px (27h).
        BufferedImage current = square;
        int width = size;
        while (width / 2 > RENDER_PX) {
            width /= 2;
            current = draw(current, width);
        }
        byte[] jpeg = encode(draw(current, RENDER_PX));
        image.flush();
        return jpeg;
    }

    private BufferedImage draw(BufferedImage from, int to) {
        // TYPE_INT_RGB, not ARGB: the target is a JPEG, and a transparent PNG drawn onto an
        // undefined background comes out black. White is the app's own paper.
        BufferedImage target = new BufferedImage(to, to, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, to, to);
            g.drawImage(from, 0, 0, to, to, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("no JPEG writer");
        }
        return out.toByteArray();
    }

    /**
     * @param updatedAt when these bytes landed, so the response can be cached hard — the
     *                  URL that asked for them already carries it.
     */
    public record Download(GetObjectResponse stream, Instant updatedAt) {}
}
