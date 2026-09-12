package com.rekordo.services.sync;

import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.PhotoEntity;
import com.rekordo.entity.WishlistItemEntity;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.model.core.SyncCopyDto;
import com.rekordo.model.core.SyncPhotoDto;
import com.rekordo.model.core.SyncPullDto;
import com.rekordo.model.core.SyncWishDto;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import com.rekordo.model.core.CopyOrigin;
import com.rekordo.repository.WishlistItemRepository;
import com.rekordo.services.metadata.MetadataService;
import com.rekordo.services.social.ActivityService;
import com.rekordo.services.storage.PhotoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's half of sync.
 *
 * The server is a merge participant, not just storage: a pushed copy is merged against
 * what is already stored using the same contract the clients use ({@link CopyMerge}), and
 * the merged result is what gets saved and returned. That is what lets two devices that
 * have never spoken to each other converge through the server.
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** Bounded so one enormous collection cannot be pulled in a single response. */
    private static final int PULL_PAGE_SIZE = 500;

    private static final TypeReference<Map<String, String>> CLOCKS = new TypeReference<>() {};

    private final CopyRepository copyRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final PhotoRepository photoRepository;
    private final com.rekordo.services.storage.StorageService storageService;
    private final ActivityService activityService;
    private final MetadataService metadataService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Transactional(readOnly = true)
    public SyncPullDto pull(UUID userId, long since) {
        List<CopyEntity> copies =
                copyRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(userId, since);
        List<WishlistItemEntity> wishes =
                wishlistItemRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(userId, since);
        List<PhotoEntity> photos =
                photoRepository.findAllByUserIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(userId, since);

        Page<CopyEntity> copyPage = page(copies, CopyEntity::getSyncSeq);
        Page<WishlistItemEntity> wishPage = page(wishes, WishlistItemEntity::getSyncSeq);
        Page<PhotoEntity> photoPage = page(photos, PhotoEntity::getSyncSeq);
        List<Page<?>> pages = List.of(copyPage, wishPage, photoPage);

        boolean hasMore = pages.stream().anyMatch(Page::truncated);
        long cursor;
        if (hasMore) {
            // A page was cut short, so the cursor must stop at the lowest point every kind
            // is complete up to. Advancing past a record that was not sent would leave it
            // stranded on the server with the client believing it is up to date.
            cursor = pages.stream()
                    .filter(Page::truncated)
                    .mapToLong(Page::lastSeq)
                    .min()
                    .orElse(since);
        } else {
            // Nothing was withheld, so the cursor can take the high-water mark of them all.
            cursor = pages.stream().mapToLong(Page::lastSeq).filter(seq -> seq > 0).max().orElse(since);
            cursor = Math.max(cursor, since);
        }

        final long limit = cursor;
        return new SyncPullDto(
                copyPage.upTo(limit, CopyEntity::getSyncSeq).stream().map(this::toDto).toList(),
                wishPage.upTo(limit, WishlistItemEntity::getSyncSeq).stream().map(this::toWishDto).toList(),
                photoPage.upTo(limit, PhotoEntity::getSyncSeq).stream().map(this::toPhotoDto).toList(),
                limit,
                hasMore);
    }

    /** One kind's slice of a pull, with whether it had to be cut short. */
    private record Page<T>(List<T> rows, boolean truncated, long lastSeq) {
        List<T> upTo(long limit, java.util.function.ToLongFunction<T> seq) {
            return rows.stream().filter(row -> seq.applyAsLong(row) <= limit).toList();
        }
    }

    private <T> Page<T> page(List<T> all, java.util.function.ToLongFunction<T> seq) {
        boolean truncated = all.size() > PULL_PAGE_SIZE;
        List<T> rows = truncated ? all.subList(0, PULL_PAGE_SIZE) : all;
        long lastSeq = rows.isEmpty() ? 0 : seq.applyAsLong(rows.getLast());
        return new Page<>(rows, truncated, lastSeq);
    }

    @Transactional
    public SyncPullDto push(
            UUID userId,
            List<SyncCopyDto> incoming,
            List<SyncWishDto> incomingWishes,
            List<SyncPhotoDto> incomingPhotos,
            List<ReleaseDto> incomingReleases,
            Map<String, String> origins) {
        // Before the copies, so a release is never briefly nameable by nobody: the moment a
        // copy is visible to another device, the row that describes it is already here.
        metadataService.adoptFromClient(incomingReleases);
        Pushed<SyncCopyDto> copies = pushCopies(userId, incoming, origins);
        Pushed<SyncWishDto> wishes = pushWishes(userId, incomingWishes);
        Pushed<SyncPhotoDto> photos = pushPhotos(userId, incomingPhotos);
        long cursor = Math.max(copies.highWaterMark(), Math.max(wishes.highWaterMark(), photos.highWaterMark()));
        return new SyncPullDto(copies.records(), wishes.records(), photos.records(), cursor, false);
    }

    private Pushed<SyncPhotoDto> pushPhotos(UUID userId, List<SyncPhotoDto> incoming) {
        if (incoming.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<SyncPhotoDto> accepted = incoming.stream().filter(this::storable).toList();
        if (accepted.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<UUID> ids = accepted.stream().map(photo -> idOf(photo.id())).toList();
        Map<UUID, PhotoEntity> stored = new HashMap<>();
        for (PhotoEntity entity : photoRepository.findAllByUserIdAndIdIn(userId, ids)) {
            stored.put(entity.getId(), entity);
        }

        List<SyncPhotoDto> results = new ArrayList<>(accepted.size());
        long highWaterMark = 0;
        for (SyncPhotoDto client : accepted) {
            UUID id = idOf(client.id());
            PhotoEntity entity = stored.get(id);
            SyncPhotoDto merged =
                    ownBytes(userId, id, entity, PhotoMerge.merge(entity == null ? null : toPhotoDto(entity), client));

            PhotoEntity target = entity;
            if (target == null) {
                target = new PhotoEntity();
                target.setId(id);
                target.setUserId(userId);
            }
            applyPhoto(target, merged);
            target.setSyncSeq(copyRepository.nextSyncSeq());
            photoRepository.save(target);

            // A deleted photo's bytes are no longer anybody's: remove the object rather
            // than paying to store a picture no client will ever ask for again.
            if (merged.deletedAt() != null && merged.storageKey() != null) {
                storageService.delete(merged.storageKey());
            }

            highWaterMark = Math.max(highWaterMark, target.getSyncSeq());
            results.add(merged);
        }

        log.debug("Merged {} photos for user {}", results.size(), userId);
        return new Pushed<>(results, highWaterMark);
    }

    /**
     * Takes back the three fields that name bytes.
     *
     * <p>{@code storageKey}, {@code contentType} and {@code byteSize} travel through the
     * merge like everything else, because a device has to be able to tell the others that an
     * upload finished. But they are not the client's to assert: the key decides *whose*
     * object is served and deleted, and the type decides what a browser does with it. A push
     * naming {@code <someone-else>/<their-photo>} would otherwise be served that picture on
     * demand -- the row is the caller's, so {@link com.rekordo.services.storage.PhotoService}
     * asks nothing further -- and a tombstone naming it would delete it.
     *
     * <p>So the server answers for all three. A row it already holds keeps what the upload
     * endpoint wrote; a row it is meeting for the first time gets the key those two ids
     * derive, a type off the allowlist rather than whatever the push said, and a size of
     * zero. The client is not told off for it: a push that fails costs the whole batch and
     * comes back for ever (see {@link #storable(String, String, Long)}), and correcting
     * three fields is cheaper than that for the honest client that got them wrong.
     *
     * <p>Zero rather than the pushed figure, because the size is what the allowance is
     * counted from and only the upload endpoint has ever measured it. A client that could
     * assert it could assert a negative one, and a negative one makes
     * {@link com.rekordo.services.storage.StorageUsageService} read the whole account as
     * owing nothing -- so the 20 MB ceiling stops applying to it. Nothing legitimate is lost:
     * a live photo reaches a push only after its bytes were uploaded, and the upload wrote
     * the row this branch is for the absence of.
     *
     * <p>A null key stays null. That is a picture deleted before its upload finished, and the
     * tombstone is the only thing carrying the delete to the other devices.
     */
    private SyncPhotoDto ownBytes(UUID userId, UUID id, PhotoEntity stored, SyncPhotoDto merged) {
        boolean known = stored != null && stored.getStorageKey() != null;
        String storageKey = known
                ? stored.getStorageKey()
                : merged.storageKey() == null ? null : PhotoService.objectKey(userId, id);
        String contentType = known ? stored.getContentType() : PhotoService.servableType(merged.contentType());
        Long byteSize = known ? stored.getByteSize() : 0L;

        if (merged.storageKey() != null && !merged.storageKey().equals(storageKey)) {
            // Worth saying out loud: no client this project ships can produce one, so a
            // mismatch is either a build nobody remembers or somebody reaching for another
            // account's bytes.
            log.warn("Push for photo {} named storage key {} and did not get it", id, merged.storageKey());
        }
        if (merged.byteSize() != null && merged.byteSize() < 0) {
            // Only the negative case. An honest client's figure can differ from the stored
            // one by a byte or two -- it measures the blob it picked, the upload endpoint
            // measures what arrived -- and warning on every such difference would bury the
            // line that matters. A size below zero is nobody's rounding error: it is the
            // one value that makes the allowance stop applying.
            log.warn("Push for photo {} named a negative byte size ({})", id, merged.byteSize());
        }

        return new SyncPhotoDto(
                merged.id(),
                merged.copyId(),
                merged.wishId(),
                storageKey,
                contentType,
                byteSize,
                merged.sortIndex(),
                merged.createdAt(),
                merged.deletedAt(),
                merged.fieldClocks());
    }

    private void applyPhoto(PhotoEntity entity, SyncPhotoDto dto) {
        // Either owner may be absent: a photo pictures a copy or a wishlist entry, never
        // both. Parsed leniently rather than assumed, because an owner-less row from a
        // client this build has not met is unreachable, not dangerous.
        entity.setCopyId(parseId(dto.copyId()));
        entity.setWishId(parseId(dto.wishId()));
        entity.setStorageKey(dto.storageKey());
        entity.setContentType(dto.contentType() == null ? PhotoService.FALLBACK_TYPE : dto.contentType());
        // Floored as well as owned by ownBytes above. The column is what the allowance is
        // summed from, and a belt-and-braces clamp here is what makes the CHECK constraint
        // V48 adds something the app can never trip rather than a 500 waiting to happen.
        entity.setByteSize(dto.byteSize() == null ? 0L : Math.max(0L, dto.byteSize()));
        entity.setSortIndex(dto.sortIndex() == null ? 0 : dto.sortIndex());
        entity.setCreatedAt(dto.createdAt());
        entity.setDeletedAt(dto.deletedAt());
        entity.setFieldClocks(writeClocks(dto.fieldClocks()));
    }

    private static UUID parseId(String value) {
        return value == null || value.isBlank() ? null : idOf(value);
    }

    /**
     * A well-formed UUID, or null.
     *
     * <p>Lenient on purpose. Every id in a batch is parsed before anything is written, so a
     * single unparseable one used to throw out of the whole request -- and since a client
     * only clears its pending set on a successful push, it would send that same batch again
     * every minute for ever. One bad record must cost that record, not the device.
     */
    private static UUID idOf(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    private SyncPhotoDto toPhotoDto(PhotoEntity entity) {
        return new SyncPhotoDto(
                entity.getId().toString(),
                entity.getCopyId() == null ? null : entity.getCopyId().toString(),
                entity.getWishId() == null ? null : entity.getWishId().toString(),
                entity.getStorageKey(),
                entity.getContentType(),
                entity.getByteSize(),
                entity.getSortIndex(),
                entity.getCreatedAt(),
                entity.getDeletedAt(),
                readClocks(entity.getFieldClocks()));
    }

    /**
     * Whether a pushed record can be stored at all.
     *
     * <p>Push is one transaction, so a row that cannot be written does not fail alone: it
     * takes the whole batch with it. And a client only clears its pending set once the push
     * has succeeded, so the same doomed batch comes back every sync -- every record written
     * on that device afterwards queued behind a row that will never land. One photo deleted
     * a second after it was taken is enough to freeze a phone's push for days.
     *
     * <p>So a record the schema cannot hold is dropped here, before anything is written, and
     * said out loud. Dropping it is not free -- the client hears no objection and clears it
     * from pending -- but it is the cheaper of the two losses: the alternative costs every
     * *other* record on that device as well, indefinitely.
     */
    private boolean storable(String kind, String id, Long createdAt) {
        if (idOf(id) == null) {
            drop(kind, "malformed_id", id);
            return false;
        }
        if (createdAt == null) {
            drop(kind, "no_created_at", id);
            return false;
        }
        return true;
    }

    /**
     * Every drop counter, at zero, before anything has been dropped.
     *
     * <p>A counter that is created on first use does not exist while everything is fine,
     * and a series that does not exist cannot be charted and cannot be alerted on -- the
     * panel reads "no data", which looks the same as a panel that is broken. Registering
     * the whole set up front means the dashboard shows a flat zero, and the flat zero is
     * the thing worth seeing.
     */
    @jakarta.annotation.PostConstruct
    void registerDropCounters() {
        for (String kind : List.of("copy", "wish", "photo")) {
            for (String reason : List.of("malformed_id", "no_created_at", "live_without_storage_key")) {
                // A photo is the only kind that can be dropped for want of a storage key.
                if (reason.equals("live_without_storage_key") && !kind.equals("photo")) {
                    continue;
                }
                dropCounter(kind, reason);
            }
        }
    }

    private Counter dropCounter(String kind, String reason) {
        return Counter.builder("rekordo.sync.push.dropped")
                .description("Records a push had to throw away because they cannot be stored")
                .tag("kind", kind)
                .tag("reason", reason)
                .register(meterRegistry);
    }

    /**
     * Records a record thrown away, and says which kind and why.
     *
     * <p>Counted, not only logged. Before this, an unstorable row announced itself as a 500
     * on every push -- loud, and impossible to miss once anybody looked. Dropping it instead
     * is the right behaviour but it is also silent: the client hears success, the user sees
     * their record on the device that made it, and nothing anywhere says it did not
     * replicate. This counter is the only thing standing between that and a repeat of the
     * bug it was written for, so it is worth an alert on any non-zero rate.
     */
    private void drop(String kind, String reason, String id) {
        log.warn("Dropping {} {} from push: {}", kind, id, reason);
        dropCounter(kind, reason).increment();
    }

    /**
     * As {@link #storable(String, String, Long)}, plus the one rule specific to photos.
     *
     * <p>A live photo with no storage key names bytes that are nowhere: no other device can
     * ever fetch it, and the clients do not send one. A *deleted* photo with no key is
     * ordinary and must be kept -- that is a picture deleted before its upload finished, and
     * the tombstone is the only thing that carries the delete to the other devices.
     */
    private boolean storable(SyncPhotoDto photo) {
        if (!storable("photo", photo.id(), photo.createdAt())) {
            return false;
        }
        if (photo.storageKey() == null && photo.deletedAt() == null) {
            drop("photo", "live_without_storage_key", photo.id());
            return false;
        }
        return true;
    }

    /**
     * The merged records plus the highest sequence written.
     *
     * Returned rather than kept in a field: this service is a singleton, so per-request
     * state on the instance would be shared by every concurrent request.
     */
    private record Pushed<T>(List<T> records, long highWaterMark) {}

    private Pushed<SyncCopyDto> pushCopies(UUID userId, List<SyncCopyDto> incoming, Map<String, String> origins) {
        if (incoming.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<SyncCopyDto> accepted =
                incoming.stream().filter(copy -> storable("copy", copy.id(), copy.createdAt())).toList();
        if (accepted.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<UUID> ids = accepted.stream().map(copy -> idOf(copy.id())).toList();
        Map<UUID, CopyEntity> stored = new HashMap<>();
        for (CopyEntity entity : copyRepository.findAllByUserIdAndIdIn(userId, ids)) {
            stored.put(entity.getId(), entity);
        }

        List<SyncCopyDto> results = new ArrayList<>(accepted.size());
        long highWaterMark = 0;

        for (SyncCopyDto client : accepted) {
            UUID id = idOf(client.id());
            CopyEntity entity = stored.get(id);
            boolean created = entity == null;
            SyncCopyDto merged = CopyMerge.merge(entity == null ? null : toDto(entity), client);

            CopyEntity target = entity == null ? newEntity(id, userId) : entity;
            apply(target, merged);
            // A fresh sequence on every write, whether or not the merge changed anything —
            // a client that pushed must be able to see its own push come back on the next
            // pull, or it would loop trying to resend.
            target.setSyncSeq(copyRepository.nextSyncSeq());
            copyRepository.save(target);

            announce(userId, id, created, merged, origins);

            highWaterMark = Math.max(highWaterMark, target.getSyncSeq());
            results.add(merged);
        }

        log.debug("Merged {} copies for user {}", results.size(), userId);
        return new Pushed<>(results, highWaterMark);
    }

    /**
     * Tell the actor's friends, or take back what was said.
     *
     * <p>Only a row the server had never seen announces anything: an edit pushes the same
     * copy again, and a record does not become news twice. A tombstone withdraws the line,
     * because a feed saying somebody added a record they have since deleted is a claim about
     * them that is no longer true.
     */
    private void announce(UUID userId, UUID copyId, boolean created, SyncCopyDto merged, Map<String, String> origins) {
        if (merged.deletedAt() != null) {
            activityService.forget(userId, copyId);
            return;
        }
        if (!created) {
            return;
        }
        activityService.recordCopyAdded(
                userId,
                copyId,
                originOf(origins, merged.id()),
                merged.releaseId(),
                merged.albumId(),
                merged.manualTitle(),
                merged.manualArtist(),
                merged.createdAt());
    }

    /** An origin the client did not send, or one it sent that this build does not know, is silence. */
    private static CopyOrigin originOf(Map<String, String> origins, String copyId) {
        String raw = origins == null ? null : origins.get(copyId);
        if (raw == null) {
            return null;
        }
        try {
            return CopyOrigin.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Pushed<SyncWishDto> pushWishes(UUID userId, List<SyncWishDto> incoming) {
        if (incoming.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<SyncWishDto> accepted =
                incoming.stream().filter(wish -> storable("wish", wish.id(), wish.createdAt())).toList();
        if (accepted.isEmpty()) {
            return new Pushed<>(List.of(), 0);
        }

        List<UUID> ids = accepted.stream().map(wish -> idOf(wish.id())).toList();
        Map<UUID, WishlistItemEntity> stored = new HashMap<>();
        for (WishlistItemEntity entity : wishlistItemRepository.findAllByUserIdAndIdIn(userId, ids)) {
            stored.put(entity.getId(), entity);
        }

        List<SyncWishDto> results = new ArrayList<>(accepted.size());
        long highWaterMark = 0;
        for (SyncWishDto client : accepted) {
            UUID id = idOf(client.id());
            WishlistItemEntity entity = stored.get(id);
            boolean created = entity == null;
            SyncWishDto merged = WishMerge.merge(entity == null ? null : toWishDto(entity), client);

            WishlistItemEntity target = entity;
            if (target == null) {
                target = new WishlistItemEntity();
                target.setId(id);
                target.setUserId(userId);
            }
            applyWish(target, merged);
            target.setSyncSeq(copyRepository.nextSyncSeq());
            wishlistItemRepository.save(target);

            // A wishlist is a list of things somebody is hunting for, which is exactly the
            // sort of thing a friend can help with -- so wishes announce themselves whatever
            // the batch's origin, and stop when the wish is taken back.
            if (merged.deletedAt() != null) {
                activityService.forget(userId, id);
            } else if (created) {
                activityService.recordWishAdded(
                        userId,
                        id,
                        merged.albumId(),
                        merged.title(),
                        merged.artistName(),
                        merged.desiredFormat(),
                        merged.createdAt());
            }

            highWaterMark = Math.max(highWaterMark, target.getSyncSeq());
            results.add(merged);
        }

        log.debug("Merged {} wishes for user {}", results.size(), userId);
        return new Pushed<>(results, highWaterMark);
    }

    private void applyWish(WishlistItemEntity entity, SyncWishDto dto) {
        entity.setAlbumId(dto.albumId());
        entity.setReleaseId(dto.releaseId());
        entity.setPendingBarcode(dto.pendingBarcode());
        entity.setTitle(dto.title() == null ? "Untitled" : dto.title());
        entity.setArtistName(dto.artistName() == null ? "Unknown artist" : dto.artistName());
        entity.setYear(dto.year());
        entity.setDesiredFormat(dto.desiredFormat());
        entity.setNote(dto.note());
        entity.setSortIndex(dto.sortIndex());
        entity.setCreatedAt(dto.createdAt());
        entity.setDeletedAt(dto.deletedAt());
        entity.setFieldClocks(writeClocks(dto.fieldClocks()));
    }

    private SyncWishDto toWishDto(WishlistItemEntity entity) {
        return new SyncWishDto(
                entity.getId().toString(),
                entity.getAlbumId(),
                entity.getReleaseId(),
                entity.getPendingBarcode(),
                entity.getTitle(),
                entity.getArtistName(),
                entity.getYear(),
                entity.getDesiredFormat(),
                entity.getNote(),
                entity.getSortIndex(),
                entity.getCreatedAt(),
                entity.getDeletedAt(),
                readClocks(entity.getFieldClocks()));
    }

    private CopyEntity newEntity(UUID id, UUID userId) {
        CopyEntity entity = new CopyEntity();
        entity.setId(id);
        entity.setUserId(userId);
        return entity;
    }

    private void apply(CopyEntity entity, SyncCopyDto dto) {
        entity.setReleaseId(dto.releaseId());
        entity.setAlbumId(dto.albumId());
        entity.setPendingBarcode(dto.pendingBarcode());
        entity.setManualTitle(dto.manualTitle());
        entity.setManualArtist(dto.manualArtist());
        entity.setManualYear(dto.manualYear());
        entity.setManualLabel(dto.manualLabel());
        entity.setManualCatalogNumber(dto.manualCatalogNumber());
        entity.setManualFormat(dto.manualFormat());
        entity.setCondition(dto.condition());
        entity.setSleeveCondition(dto.sleeveCondition());
        // Absent means a client older than the field, which is the same as having said nothing.
        entity.setCatalogArt(dto.catalogArt() == null ? "AUTO" : dto.catalogArt());
        entity.setPricePaidCents(dto.pricePaidCents());
        entity.setCurrency(dto.currency() == null ? "EUR" : dto.currency());
        entity.setPurchasedOn(dto.purchasedOn());
        entity.setPurchasedAt(dto.purchasedAt());
        entity.setNotes(dto.notes());
        entity.setNotesConflict(dto.notesConflict());
        entity.setRating(dto.rating());
        // Absent means a client older than the field, which is the same as not hidden.
        entity.setHidden(Boolean.TRUE.equals(dto.hidden()));
        // Absent means a client older than the field, which is the same as never placed.
        entity.setSortIndex(dto.sortIndex());
        entity.setCreatedAt(dto.createdAt());
        entity.setDeletedAt(dto.deletedAt());
        entity.setFieldClocks(writeClocks(dto.fieldClocks()));
    }

    private SyncCopyDto toDto(CopyEntity entity) {
        return new SyncCopyDto(
                entity.getId().toString(),
                entity.getReleaseId(),
                entity.getAlbumId(),
                entity.getPendingBarcode(),
                entity.getManualTitle(),
                entity.getManualArtist(),
                entity.getManualYear(),
                entity.getManualLabel(),
                entity.getManualCatalogNumber(),
                entity.getManualFormat(),
                entity.getCondition(),
                entity.getSleeveCondition(),
                entity.getCatalogArt(),
                entity.getPricePaidCents(),
                entity.getCurrency(),
                entity.getPurchasedOn(),
                entity.getPurchasedAt(),
                entity.getNotes(),
                entity.getNotesConflict(),
                entity.getRating(),
                entity.isHidden(),
                entity.getSortIndex(),
                entity.getCreatedAt(),
                entity.getDeletedAt(),
                readClocks(entity.getFieldClocks()));
    }

    // Spring Boot 4.1 auto-configures Jackson 3 (tools.jackson), not the 2.x ObjectMapper.
    // Both are on the classpath, so injecting the wrong one fails at startup rather than at
    // runtime -- which is how this was found.
    private String writeClocks(Map<String, String> clocks) {
        return objectMapper.writeValueAsString(clocks == null ? Map.of() : clocks);
    }

    private Map<String, String> readClocks(String json) {
        return objectMapper.readValue(json, CLOCKS);
    }
}
