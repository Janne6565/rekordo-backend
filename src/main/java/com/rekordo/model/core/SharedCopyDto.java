package com.rekordo.model.core;

/**
 * A copy as somebody else sees it, which is deliberately less than the owner sees.
 *
 * <p>Notes never appear here at all — they are the one field written for nobody else — and
 * the grades and the money are filled in only when the viewer has earned them. Fields the
 * viewer may not see are null rather than absent, so one shape serves the friend view and
 * the public page and no client has to branch on which it asked for.
 */
public record SharedCopyDto(
        String id,
        String releaseId,
        /** Already resolved: the copy's own title when it was typed in by hand. */
        String title,
        String artistName,
        Integer year,
        /** Already resolved: the copy's override wins over the catalogue's answer. */
        Format format,
        /**
         * The archive's artwork, or null when this copy has hidden it or there is none.
         * Mirrors {@code catalogArtShown} on the clients -- hiding it is a decision about
         * this copy's images, and a shelf somebody else reads must honour it too.
         */
        String coverArtUrl,
        /**
         * The copy's own first photo, as a photo id the client turns into a content URL.
         *
         * The only picture a hand-entered copy can ever have: it points at no catalogue, so
         * there is no cover art to fall back to. Null when the copy has no photos, or when
         * it has starred the catalogue artwork instead ({@code copyPreviewSrc}).
         */
        String previewPhotoId,
        CoverThemeDto coverTheme,
        /** Media grade. Friends and up; null on a public page. */
        String condition,
        /** Sleeve grade. Friends and up; null on a public page. */
        String sleeveCondition,
        /**
         * One to five stars, or null: both when the copy is unrated and when the owner has
         * not turned ratings on. The clients draw nothing either way, so the two cases look
         * the same from outside, which is the point.
         */
        Integer rating,
        /** Null unless the owner has turned prices on. */
        Integer pricePaidCents,
        String currency,
        Long createdAt) {}
