package com.rekordo.repository;

import com.rekordo.entity.WishlistItemEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface WishlistItemRepository extends JpaRepository<WishlistItemEntity, UUID> {

    List<WishlistItemEntity> findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(UUID userId, long since);

    List<WishlistItemEntity> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    /**
     * What someone else may see of a wishlist. Entries still waiting for a name are left
     * out for the same reason pending copies are — see {@code CopyRepository.findVisible},
     * which also explains the order: the owner's own arrangement, unplaced entries last.
     */
    @Query("""
            SELECT w FROM WishlistItemEntity w
            WHERE w.userId = :userId AND w.deletedAt IS NULL AND w.pendingBarcode IS NULL
            ORDER BY w.sortIndex ASC NULLS LAST, w.createdAt DESC, w.id
            """)
    List<WishlistItemEntity> findVisible(@Param("userId") UUID userId, Pageable pageable);

    @Query("""
            SELECT COUNT(w) FROM WishlistItemEntity w
            WHERE w.userId = :userId AND w.deletedAt IS NULL AND w.pendingBarcode IS NULL
            """)
    long countVisible(@Param("userId") UUID userId);

    /** Whether this album is one they have been hunting for. */
    List<WishlistItemEntity> findAllByUserIdAndAlbumIdAndDeletedAtIsNull(UUID userId, String albumId);
}
