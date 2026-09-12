package com.rekordo.services.sync;

import com.rekordo.model.core.SyncCopyDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Field-level last-write-wins merge for a copy — the Java half of a contract shared with
 * both clients.
 *
 * The specification is {@code merge-fixture.json}, hand-authored and committed to all
 * three repositories. This implementation and the TypeScript one are each tested against
 * it, so neither can quietly become the definition of correct.
 *
 * Clocks are compared as their encoded strings. The encoding is fixed width, so
 * lexicographic order is clock order, and {@link String#compareTo} agrees with
 * JavaScript's string comparison for the ASCII ids in use.
 */
public final class CopyMerge {

    /**
     * The fields that carry their own clock and merge independently. Order matters only
     * for readability; the merge is per key.
     */
    public static final List<String> MERGEABLE_FIELDS = List.of(
            "releaseId",
            "albumId",
            "manualTitle",
            "manualArtist",
            "manualYear",
            "manualLabel",
            "manualCatalogNumber",
            "manualFormat",
            "pendingBarcode",
            "condition",
            "sleeveCondition",
            "catalogArt",
            "pricePaidCents",
            "currency",
            "purchasedOn",
            "purchasedAt",
            "notes",
            "rating",
            "hidden",
            "sortIndex",
            "deletedAt");

    private CopyMerge() {}

    public static SyncCopyDto merge(SyncCopyDto local, SyncCopyDto remote) {
        if (local == null && remote == null) {
            throw new IllegalArgumentException("merge needs at least one side");
        }
        if (local == null) {
            return remote;
        }
        if (remote == null) {
            return local;
        }
        if (!Objects.equals(local.id(), remote.id())) {
            throw new IllegalArgumentException(
                    "merge got two different copies: " + local.id() + " vs " + remote.id());
        }

        Map<String, String> clocks = new LinkedHashMap<>();
        Map<String, Object> values = new HashMap<>();

        for (String field : MERGEABLE_FIELDS) {
            String localClock = clockOf(local, field);
            String remoteClock = clockOf(remote, field);
            boolean remoteWins = remoteWins(localClock, remoteClock);

            values.put(field, valueOf(remoteWins ? remote : local, field));
            clocks.put(field, remoteWins ? remoteClock : localClock);
        }

        String winningNotes = (String) values.get("notes");

        return new SyncCopyDto(
                local.id(),
                (String) values.get("releaseId"),
                (String) values.get("albumId"),
                (String) values.get("pendingBarcode"),
                (String) values.get("manualTitle"),
                (String) values.get("manualArtist"),
                (Integer) values.get("manualYear"),
                (String) values.get("manualLabel"),
                (String) values.get("manualCatalogNumber"),
                (String) values.get("manualFormat"),
                (String) values.get("condition"),
                (String) values.get("sleeveCondition"),
                (String) values.get("catalogArt"),
                (Integer) values.get("pricePaidCents"),
                (String) values.get("currency"),
                (String) values.get("purchasedOn"),
                (String) values.get("purchasedAt"),
                winningNotes,
                losingNotes(local, remote, winningNotes),
                (Integer) values.get("rating"),
                (Boolean) values.get("hidden"),
                (Integer) values.get("sortIndex"),
                // The same record cannot have been created twice, so the earlier timestamp
                // is the true one.
                Math.min(local.createdAt(), remote.createdAt()),
                (Long) values.get("deletedAt"),
                clocks);
    }

    /**
     * The other version of the notes this record currently knows about.
     *
     * <p>A state rather than an event: it means "some peer has different notes", and it
     * survives until the person edits the notes themselves or every peer converges.
     * Carrying an existing conflict forward is what keeps the merge idempotent — a client
     * merges, pushes, pulls the same record back and merges again, and that round trip must
     * not quietly drop the marker.
     */
    private static String losingNotes(SyncCopyDto local, SyncCopyDto remote, String winning) {
        if (!meaningful(winning)) {
            return null;
        }
        // Exactly one of the two notes values can differ from the winner, so this does not
        // depend on which side was passed first.
        for (String candidate : List.of(safe(local.notes()), safe(remote.notes()))) {
            if (meaningful(candidate) && !candidate.equals(winning)) {
                return candidate;
            }
        }
        // Neither side edited notes this round; keep whatever was already recorded. Sorted
        // rather than taken in argument order, so both peers pick the same one.
        List<String> carried = new ArrayList<>();
        for (String candidate : List.of(safe(local.notesConflict()), safe(remote.notesConflict()))) {
            if (meaningful(candidate) && !candidate.equals(winning)) {
                carried.add(candidate);
            }
        }
        carried.sort(String::compareTo);
        return carried.isEmpty() ? null : carried.getFirst();
    }

    private static boolean remoteWins(String localClock, String remoteClock) {
        if (remoteClock == null) {
            return false;
        }
        if (localClock == null) {
            return true;
        }
        return remoteClock.compareTo(localClock) > 0;
    }

    private static String clockOf(SyncCopyDto copy, String field) {
        Map<String, String> clocks = copy.fieldClocks();
        return clocks == null ? null : clocks.get(field);
    }

    private static Object valueOf(SyncCopyDto copy, String field) {
        return switch (field) {
            case "releaseId" -> copy.releaseId();
            case "albumId" -> copy.albumId();
            case "manualTitle" -> copy.manualTitle();
            case "manualArtist" -> copy.manualArtist();
            case "manualYear" -> copy.manualYear();
            case "manualLabel" -> copy.manualLabel();
            case "manualCatalogNumber" -> copy.manualCatalogNumber();
            case "manualFormat" -> copy.manualFormat();
            case "pendingBarcode" -> copy.pendingBarcode();
            case "condition" -> copy.condition();
            case "sleeveCondition" -> copy.sleeveCondition();
            case "catalogArt" -> copy.catalogArt();
            case "pricePaidCents" -> copy.pricePaidCents();
            case "currency" -> copy.currency();
            case "purchasedOn" -> copy.purchasedOn();
            case "purchasedAt" -> copy.purchasedAt();
            case "notes" -> copy.notes();
            case "rating" -> copy.rating();
            case "hidden" -> copy.hidden();
            case "sortIndex" -> copy.sortIndex();
            case "deletedAt" -> copy.deletedAt();
            default -> throw new IllegalArgumentException("Not a mergeable field: " + field);
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean meaningful(String notes) {
        return notes != null && !notes.isBlank();
    }

    /** Merges two collections keyed by copy id. */
    public static List<SyncCopyDto> mergeAll(
            Collection<SyncCopyDto> local, Collection<SyncCopyDto> remote) {
        Map<String, SyncCopyDto[]> byId = new LinkedHashMap<>();
        for (SyncCopyDto copy : local) {
            byId.computeIfAbsent(copy.id(), unused -> new SyncCopyDto[2])[0] = copy;
        }
        for (SyncCopyDto copy : remote) {
            byId.computeIfAbsent(copy.id(), unused -> new SyncCopyDto[2])[1] = copy;
        }
        return byId.values().stream().map(pair -> merge(pair[0], pair[1])).toList();
    }
}
