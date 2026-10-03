package com.rekordo.repository;

import com.rekordo.entity.PhotoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {

    List<PhotoEntity> findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(UUID userId, long since);

    List<PhotoEntity> findAllByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    List<PhotoEntity> findAllByUserId(UUID userId);

    /**
     * Live photos whose copy or wish was removed before {@code removedBefore}, a batch at a time.
     *
     * <p>Removing a record has only ever tombstoned the record, so its photos stayed live --
     * counted by {@link #sumLiveBytes} and kept in the bucket with nothing able to reach them.
     * The clients now put them down themselves; this is what finds the ones a client never
     * will, because it is an old version or never opens again. The owner has to be the same
     * account's: photos carry no foreign key to their owner, and an id alone is not proof.
     */
    @Query(value = """
            SELECT p.* FROM photos p
            WHERE p.deleted_at IS NULL
              AND (EXISTS (SELECT 1 FROM copies c
                           WHERE c.id = p.copy_id AND c.user_id = p.user_id
                             AND c.deleted_at IS NOT NULL AND c.deleted_at < :removedBefore)
                OR EXISTS (SELECT 1 FROM wishlist_items w
                           WHERE w.id = p.wish_id AND w.user_id = p.user_id
                             AND w.deleted_at IS NOT NULL AND w.deleted_at < :removedBefore))
            ORDER BY p.id
            LIMIT :batch
            """, nativeQuery = true)
    List<PhotoEntity> findOrphaned(@Param("removedBefore") long removedBefore, @Param("batch") int batch);

    /** Scoped by user so one account can never read another's photo by guessing an id. */
    Optional<PhotoEntity> findByIdAndUserId(UUID id, UUID userId);

    /**
     * The live photos of these copies, in the order the strip draws them.
     *
     * One query for a whole shelf rather than one per tile: a profile page asks for up to
     * two hundred copies at once, and the first photo of each is what stands in wherever a
     * copy has no catalogue art. Uploaded-but-not-yet-stored rows are left out — a
     * `storageKey` of null is a photo whose bytes never arrived, and a URL pointing at one
     * would 404 on every tile that used it.
     */
    @Query("""
            SELECT p FROM PhotoEntity p
            WHERE p.userId = :userId
              AND p.copyId IN :copyIds
              AND p.deletedAt IS NULL
              AND p.storageKey IS NOT NULL
            ORDER BY p.sortIndex ASC, p.createdAt ASC
            """)
    List<PhotoEntity> findVisibleForCopies(@Param("userId") UUID userId, @Param("copyIds") Collection<UUID> copyIds);

    /**
     * What this account's live photos weigh, and how many there are.
     *
     * <p>The same predicate the deletion path honours: a tombstone's object is removed when
     * the delete syncs, and a row with no key never had one, so neither is anything the
     * bucket is holding. Summed in the database rather than by loading the rows -- a shelf
     * can have hundreds of photos and the answer is two numbers.
     */
    @Query("""
            SELECT coalesce(sum(p.byteSize), 0) AS bytes, count(p) AS photos
            FROM PhotoEntity p
            WHERE p.userId = :userId
              AND p.deletedAt IS NULL
              AND p.storageKey IS NOT NULL
            """)
    Usage sumLiveBytes(@Param("userId") UUID userId);

    /** The two aggregates above, named, so neither is read out of the wrong column. */
    interface Usage {
        long getBytes();

        long getPhotos();
    }
}
