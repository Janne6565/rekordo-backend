package com.rekordo.repository;

import com.rekordo.entity.CopyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CopyRepository extends JpaRepository<CopyEntity, UUID> {

    /** Everything the client has not seen yet, oldest change first. */
    List<CopyEntity> findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(UUID userId, long since);

    List<CopyEntity> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    @Query(value = "SELECT nextval('copies_sync_seq')", nativeQuery = true)
    long nextSyncSeq();

    /**
     * What someone else is allowed to see: alive, not hidden one by one, not still waiting
     * to find out what it is, oldest change last. The filter lives in the query rather
     * than in a stream afterwards so that no caller can forget it.
     *
     * <p>A copy with a pending barcode is a scan the owner's phone has not been able to
     * look up yet. On their own shelf it is a row that says so and names its digits; on
     * somebody else's screen it would be an "Untitled" placeholder that neither person can
     * act on, and it stops being one the moment any of the owner's devices gets a signal.
     *
     * <p>Ordered the way the owner arranged their shelf — the shared {@code compareManualOrder}
     * rule: placed copies by {@code sortIndex}, never-placed ones after them, newest first.
     * The order is part of what the list says, and the cut at {@code LIST_LIMIT} has to fall
     * in the same place the owner would see it.
     */
    @Query("""
            SELECT c FROM CopyEntity c
            WHERE c.userId = :userId AND c.deletedAt IS NULL AND c.hidden = FALSE
              AND c.pendingBarcode IS NULL
            ORDER BY c.sortIndex ASC NULLS LAST, c.createdAt DESC, c.id
            """)
    List<CopyEntity> findVisible(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT COUNT(c) FROM CopyEntity c
            WHERE c.userId = :userId AND c.deletedAt IS NULL AND c.hidden = FALSE
              AND c.pendingBarcode IS NULL
            """)
    long countVisible(@Param("userId") UUID userId);

    /**
     * Every copy still on the shelf, hidden ones included. The goodbye mail counts what is
     * being deleted, and a copy hidden from friends is still one of somebody's records.
     */
    long countByUserIdAndDeletedAtIsNull(UUID userId);
}
