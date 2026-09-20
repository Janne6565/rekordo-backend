package com.rekordo.services.metadata;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Samples a cover image down to two colours: the dominant tone that decides the chrome,
 * and an accent for stars and primary buttons.
 *
 * <p>Pure and Spring-free apart from the stereotype, so it can be unit-tested against
 * synthetic images.
 */
@Component
public class DominantColorExtractor {

    /** 4 bits per channel — coarse enough that near-identical pixels share a bucket. */
    private static final int QUANTISATION_SHIFT = 4;

    /** Cap the pixels examined; a 250px thumbnail is ~62k pixels and a full scan is cheap. */
    private static final int TARGET_SAMPLES = 20_000;

    /** An accent has to be colourful and mid-toned, or it reads as grey against the chrome. */
    private static final double MIN_ACCENT_SATURATION = 0.25;
    private static final double MIN_ACCENT_LIGHTNESS = 0.2;
    private static final double MAX_ACCENT_LIGHTNESS = 0.9;

    public Optional<CoverPalette> extract(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            return Optional.empty();
        }
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            return Optional.empty();
        }

        Map<Integer, Bucket> buckets = new HashMap<>();
        int step = Math.max(1, (int) Math.sqrt((double) image.getWidth() * image.getHeight() / TARGET_SAMPLES));

        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 128) {
                    continue; // effectively transparent
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int key = (r >> QUANTISATION_SHIFT) << 8
                        | (g >> QUANTISATION_SHIFT) << 4
                        | (b >> QUANTISATION_SHIFT);
                buckets.computeIfAbsent(key, unused -> new Bucket()).add(r, g, b);
            }
        }
        if (buckets.isEmpty()) {
            return Optional.empty();
        }

        Bucket dominant = buckets.values().stream().max(Bucket::compareByCount).orElseThrow();
        int[] dominantRgb = dominant.average();
        double lightness = perceptualLightness(dominantRgb[0], dominantRgb[1], dominantRgb[2]);

        String accent = buckets.values().stream()
                .filter(Bucket::isAccentCandidate)
                .max(Bucket::compareByCount)
                .map(bucket -> toHex(bucket.average()))
                .orElseGet(() -> toHex(dominantRgb));

        return Optional.of(new CoverPalette(toHex(dominantRgb), accent, lightness));
    }

    /**
     * Perceptual lightness (CIE L*), normalised to 0..1.
     *
     * <p>Deliberately not WCAG relative luminance. Luminance is linear light, so mid-grey
     * sits at 0.22 rather than near the middle — cutting at "55%" on that scale calls
     * almost every sleeve dark, including obviously pale ones. L* is the perceptual curve,
     * so 0.55 means what a person looking at the cover would call half-way.
     */
    static double perceptualLightness(int r, int g, int b) {
        double y = relativeLuminance(r, g, b);
        double f = y > 0.008856 ? Math.cbrt(y) : (903.3 * y + 16.0) / 116.0;
        return Math.clamp((116.0 * f - 16.0) / 100.0, 0.0, 1.0);
    }

    /** WCAG 2.x relative luminance — linear light, the input to {@link #perceptualLightness}. */
    static double relativeLuminance(int r, int g, int b) {
        return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b);
    }

    private static double channelLuminance(int channel) {
        double normalised = channel / 255.0;
        return normalised <= 0.04045 ? normalised / 12.92 : Math.pow((normalised + 0.055) / 1.055, 2.4);
    }

    static String toHex(int[] rgb) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    /** Accumulates the true colours that fell into one quantised bucket. */
    private static final class Bucket {
        private long sumR;
        private long sumG;
        private long sumB;
        private int count;

        void add(int r, int g, int b) {
            sumR += r;
            sumG += g;
            sumB += b;
            count++;
        }

        int[] average() {
            return new int[] {(int) (sumR / count), (int) (sumG / count), (int) (sumB / count)};
        }

        int compareByCount(Bucket other) {
            return Integer.compare(count, other.count);
        }

        boolean isAccentCandidate() {
            int[] rgb = average();
            int max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            int min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
            double saturation = max == 0 ? 0 : (double) (max - min) / max;
            double lightness = perceptualLightness(rgb[0], rgb[1], rgb[2]);
            return saturation >= MIN_ACCENT_SATURATION
                    && lightness >= MIN_ACCENT_LIGHTNESS
                    && lightness <= MAX_ACCENT_LIGHTNESS;
        }
    }
}
