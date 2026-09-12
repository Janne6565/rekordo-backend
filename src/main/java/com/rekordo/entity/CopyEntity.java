package com.rekordo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "copies")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class CopyEntity {

    /** Client-generated, so a copy created offline keeps its identity when it syncs. */
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * Which pressing, or null when nobody chose one.
     *
     * The album is what the person picked; a pressing is a refinement of it, and one most
     * people never make. Before {@code album_id} the copy had nowhere to say so, and the
     * pressing the catalogue ranked first was written down as though it had been chosen.
     */
    @Column(name = "release_id")
    private String releaseId;

    /**
     * The album this is a copy of, source-qualified, or null on a row older than the
     * column whose pressing this server has never mirrored.
     *
     * The same pair {@code WishlistItemEntity} has always carried.
     */
    @Column(name = "album_id")
    private String albumId;

    /**
     * The barcode of a scan nobody could look up yet, or null once the record has a name.
     *
     * Written by whichever client made the scan and cleared by whichever one identifies
     * it; the server only carries it. Nullable and unindexed: it is null on all but a
     * handful of rows, and the question "what is still waiting?" is asked by a client of
     * its own store, never of this table.
     */
    @Column(name = "pending_barcode")
    private String pendingBarcode;

    /**
     * What a hand-entered copy says about its own pressing, all null on a matched one.
     *
     * On the copy rather than in the `releases` mirror because they are the user's data:
     * the mirror is a shared cache of two catalogues, keyed by their ids, and a pressing
     * only one person has ever seen has no place in it.
     */
    @Column(name = "manual_title")
    private String manualTitle;

    @Column(name = "manual_artist")
    private String manualArtist;

    @Column(name = "manual_year")
    private Integer manualYear;

    @Column(name = "manual_label")
    private String manualLabel;

    @Column(name = "manual_catalog_number")
    private String manualCatalogNumber;

    @Column(name = "manual_format")
    private String manualFormat;

    /** The media grade. Kept as `condition` because it is the field that already synced. */
    @Column(name = "condition")
    private String condition;

    @Column(name = "sleeve_condition")
    private String sleeveCondition;

    /** AUTO, PREFERRED or HIDDEN — what this copy has said about the release's artwork. */
    @Column(name = "catalog_art", nullable = false)
    private String catalogArt;

    @Column(name = "price_paid_cents")
    private Integer pricePaidCents;

    @Column(nullable = false)
    private String currency;

    @Column(name = "purchased_on")
    private String purchasedOn;

    @Column(name = "purchased_at")
    private String purchasedAt;

    private String notes;

    @Column(name = "notes_conflict")
    private String notesConflict;

    private Integer rating;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    /**
     * Withheld from anyone but the owner, whatever the sharing settings say. Mergeable
     * like every other field on a copy: hiding one on the phone has to reach the laptop.
     */
    @Column(nullable = false)
    private boolean hidden;

    /**
     * Where the copy sits on a hand-arranged shelf, or null while it has never been placed
     * (V47). Nullable on purpose: null is "not placed", which is not position 0.
     */
    @Column(name = "sort_index")
    private Integer sortIndex;

    @Column(name = "deleted_at")
    private Long deletedAt;

    /** Encoded HLC per field, as JSON. Never queried into, so stored as text. */
    @Column(name = "field_clocks", nullable = false)
    private String fieldClocks;

    @Column(name = "sync_seq", nullable = false)
    private Long syncSeq;
}
