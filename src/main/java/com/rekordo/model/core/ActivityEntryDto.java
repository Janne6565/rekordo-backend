package com.rekordo.model.core;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One line of the Friends feed.
 *
 * @param copyCount       1 for an ordinary line, or how many a collapsed burst covers —
 *                        "Anna added 7 copies to their collection".
 * @param collapsedCovers a few sleeves from the burst, for the little stack the design
 *                        draws beside a collapsed line. Empty for a single.
 * @param byViewer        true when the viewer is the one who did it, which only an
 *                        accepted request can be: both people in it read the same row,
 *                        so {@code actor} is always the *other* one and this says which
 *                        sentence to write around them. False for every other type.
 */
public record ActivityEntryDto(
        UUID id,
        ActivityType type,
        ActivityActorDto actor,
        String title,
        String artistName,
        String releaseId,
        Format format,
        Integer year,
        String coverArtUrl,
        Instant occurredAt,
        int copyCount,
        List<String> collapsedCovers,
        boolean byViewer) {}
