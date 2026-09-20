package com.rekordo.services.metadata;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class DominantColorExtractorTest {

    private final DominantColorExtractor extractor = new DominantColorExtractor();

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage solid(Color color) {
        BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 120, 120);
        g.dispose();
        return image;
    }

    @Test
    void aDarkSleeveAsksForDarkChrome() throws IOException {
        Optional<CoverPalette> palette = extractor.extract(png(solid(new Color(0x2e, 0x2b, 0x24))));

        assertThat(palette).hasValueSatisfying(p -> {
            assertThat(p.dominantColor()).isEqualTo("#2e2b24");
            assertThat(p.lightness()).isLessThan(CoverPalette.DARK_CHROME_THRESHOLD);
            assertThat(p.dark()).isTrue();
        });
    }

    @Test
    void aPaleSleeveAsksForLightChrome() throws IOException {
        Optional<CoverPalette> palette = extractor.extract(png(solid(new Color(0xec, 0xe8, 0xe0))));

        assertThat(palette).hasValueSatisfying(p -> {
            assertThat(p.dominantColor()).isEqualTo("#ece8e0");
            assertThat(p.dark()).isFalse();
        });
    }

    @Test
    void picksTheDominantToneWhenTwoColoursCompete() throws IOException {
        BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x14, 0x13, 0x11));
        g.fillRect(0, 0, 120, 120);
        // A quarter of the sleeve in a bright accent — colourful, but not dominant.
        g.setColor(new Color(0xd0, 0x8a, 0x5f));
        g.fillRect(0, 0, 60, 60);
        g.dispose();

        Optional<CoverPalette> palette = extractor.extract(png(image));

        assertThat(palette).hasValueSatisfying(p -> {
            assertThat(p.dominantColor()).isEqualTo("#141311");
            // The accent is drawn from the artwork, not from the dominant tone.
            assertThat(p.accentColor()).isEqualTo("#d08a5f");
            assertThat(p.dark()).isTrue();
        });
    }

    @Test
    void fallsBackToTheDominantToneWhenNothingIsColourfulEnough() throws IOException {
        Optional<CoverPalette> palette = extractor.extract(png(solid(new Color(0x80, 0x80, 0x80))));

        assertThat(palette).hasValueSatisfying(p -> assertThat(p.accentColor()).isEqualTo(p.dominantColor()));
    }

    @Test
    void returnsEmptyForBytesThatAreNotAnImage() {
        assertThat(extractor.extract("not an image".getBytes())).isEmpty();
    }

    @Test
    void whiteAndBlackSitAtTheEndsOfTheScale() {
        assertThat(DominantColorExtractor.perceptualLightness(255, 255, 255)).isEqualTo(1.0, offset(1e-6));
        assertThat(DominantColorExtractor.perceptualLightness(0, 0, 0)).isEqualTo(0.0, offset(1e-6));
    }

    @Test
    void midGreyLandsNearTheMiddleOfTheScale() {
        // The reason this is CIE L* and not WCAG relative luminance. On the luminance
        // scale mid-grey sits at 0.22, so a "below 55%" rule would call it dark — and with
        // it, almost every sleeve.
        assertThat(DominantColorExtractor.relativeLuminance(0x80, 0x80, 0x80)).isCloseTo(0.216, offset(0.01));
        assertThat(DominantColorExtractor.perceptualLightness(0x80, 0x80, 0x80)).isCloseTo(0.534, offset(0.01));
    }

    @Test
    void aPaleGreySleeveIsNotCalledDark() throws IOException {
        // Regression: the aged off-white of a scanned White Album sleeve. Under the old
        // luminance rule this measured 0.459 and was themed dark, which is plainly wrong.
        Optional<CoverPalette> palette = extractor.extract(png(solid(new Color(0xb4, 0xb6, 0xa5))));

        assertThat(palette).hasValueSatisfying(p -> {
            assertThat(p.lightness()).isGreaterThan(CoverPalette.DARK_CHROME_THRESHOLD);
            assertThat(p.dark()).isFalse();
        });
    }
}
