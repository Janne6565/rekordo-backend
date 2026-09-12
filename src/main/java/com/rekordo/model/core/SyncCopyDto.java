package com.rekordo.model.core;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.Map;

/**
 * One user-owned copy, as it travels between a client and the server.
 *
 * Field names and types match the clients' local records exactly — the merge is a shared
 * contract, so a rename here without the matching rename there would silently change which
 * side wins a conflict.
 */
public record SyncCopyDto(
        String id,
        /**
         * Source-qualified, and accepted under its old name too.
         *
         * A client deployed before the rename still pushes `releaseMbid`, and reading that
         * as absent would detach the copy from its release — silently, in a sync batch.
         * The alias makes the order of the two deploys stop mattering. It can go once no
         * client of that vintage is left.
         */
        @JsonAlias("releaseMbid") String releaseId,
        /**
         * The album, or null from a client older than the field.
         *
         * Absent rather than wrong on an old client, and absent never wins a merge -- see
         * {@code CopyMerge.remoteWins}, which keeps the server's value when the incoming
         * side carries no clock for a field. So an old client pushing a copy it has held
         * for months cannot detach it from its album.
         */
        String albumId,
        /**
         * The digits of a scan that has not been identified yet, or null once it has.
         *
         * Null from any client older than the field, which reads as "nothing pending" —
         * the only state such a client can produce.
         */
        String pendingBarcode,
        /**
         * The pressing, as it was typed in, when {@code releaseId} is
         * {@code local:<this copy's id>} and no catalogue has the record.
         *
         * Six mergeable fields rather than one object: correcting the year on one device
         * and the label on another has to keep both. Null throughout on a copy matched to
         * a real release, and null from any client older than the fields.
         */
        String manualTitle,
        String manualArtist,
        Integer manualYear,
        String manualLabel,
        String manualCatalogNumber,
        String manualFormat,
        /** The media grade, on the Goldmine scale. */
        String condition,
        /** The sleeve grade, graded separately from the media. */
        String sleeveCondition,
        /**
         * What the copy has said about the release's own artwork: {@code AUTO},
         * {@code PREFERRED} or {@code HIDDEN}.
         *
         * Null on the wire means a client older than the field, which is read as
         * {@code AUTO} the moment it lands — see {@code SyncService.apply}.
         */
        String catalogArt,
        Integer pricePaidCents,
        String currency,
        String purchasedOn,
        String purchasedAt,
        String notes,
        /** The other version of the notes this record knows about; derived by the merge. */
        String notesConflict,
        Integer rating,
        /**
         * Withheld from everybody but the owner, whatever the sharing settings say.
         *
         * Mergeable like every other field on a copy — hiding a record on the phone has to
         * reach the laptop. Null on the wire means a client older than the field, which
         * reads as not hidden.
         */
        Boolean hidden,
        /**
         * Where the copy sits on a hand-arranged shelf, or null while nobody has arranged
         * one -- which is also what every client older than the field sends, and reads the
         * same way.
         */
        Integer sortIndex,
        Long createdAt,
        Long deletedAt,
        /** Encoded HLC per mergeable field: {@code wall:counter:node}, fixed width. */
        Map<String, String> fieldClocks) {}
