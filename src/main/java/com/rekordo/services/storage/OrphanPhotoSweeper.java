package com.rekordo.services.storage;

import com.rekordo.entity.PhotoEntity;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts down the photos of removed copies and wishes that no client has put down.
 *
 * <p>The clients do this themselves now ({@code sweepOrphanPhotos} in rekordo-shared, two
 * minutes after a removal). This is the backstop for the ones that never will: an app version
 * from before that, or an account that removed a record and never opened the app again. Its
 * grace is an hour, well past the client's, so the two do not race for the same row.
 *
 * <p>A server-side write to synced data has to look like a device's write, or it is undone:
 * sync merges per field, last stamp wins, and a device only pulls rows whose {@code sync_seq}
 * moved. So each tombstone gets a fresh {@code deletedAt} clock in the clients' own HLC
 * encoding, under a node named for this job, and a new {@code sync_seq} from the sequence the
 * push path draws from -- the same three things V35 did for the wishlist.
 *
 * <p>The bytes are removed only after the tombstones commit. Deleting first and rolling back
 * would leave live rows naming objects that are gone.
 */
@Service
public class OrphanPhotoSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanPhotoSweeper.class);

    /** How long after a removal the server steps in: an hour, well past the client's two minutes. */
    static final long GRACE_MS = 60L * 60 * 1000;
    static final int BATCH = 200;
    /** Enough for any backlog in one run; a larger one simply continues on the next. */
    private static final int MAX_BATCHES = 50;
    /** Identifies a stamp no device produced, as V35's {@code server-v35} does. */
    static final String NODE = "server-orphan-sweep";
    private static final long INTERVAL_MS = 30L * 60 * 1000;

    private static final TypeReference<Map<String, String>> CLOCKS = new TypeReference<>() {};

    private final PhotoRepository photoRepository;
    private final CopyRepository copyRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    // Two constructors: without this Spring looks for a no-arg one and the context fails.
    @Autowired
    public OrphanPhotoSweeper(
            PhotoRepository photoRepository,
            CopyRepository copyRepository,
            StorageService storageService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(photoRepository, copyRepository, storageService, objectMapper,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    OrphanPhotoSweeper(
            PhotoRepository photoRepository,
            CopyRepository copyRepository,
            StorageService storageService,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            Clock clock) {
        this.photoRepository = photoRepository;
        this.copyRepository = copyRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = INTERVAL_MS)
    public void sweepOnSchedule() {
        try {
            int swept = sweep();
            if (swept > 0) {
                log.info("Put down {} photos of removed copies and wishes", swept);
            }
        } catch (RuntimeException e) {
            log.warn("Orphan photo sweep failed; it runs again in {} minutes", INTERVAL_MS / 60_000, e);
        }
    }

    /** @return how many photos were put down */
    int sweep() {
        int total = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            List<String> keys = new ArrayList<>();
            Integer swept = transactions.execute(status -> sweepBatch(keys));
            keys.forEach(storageService::delete);
            int count = swept == null ? 0 : swept;
            total += count;
            if (count < BATCH) {
                break;
            }
        }
        return total;
    }

    private int sweepBatch(List<String> storageKeys) {
        long now = clock.millis();
        List<PhotoEntity> orphans = photoRepository.findOrphaned(now - GRACE_MS, BATCH);
        String stamp = String.format("%015d:%04x:%s", now, 0, NODE);
        for (PhotoEntity photo : orphans) {
            Map<String, String> clocks = new LinkedHashMap<>(readClocks(photo.getFieldClocks()));
            clocks.put("deletedAt", stamp);
            photo.setDeletedAt(now);
            photo.setFieldClocks(objectMapper.writeValueAsString(clocks));
            photo.setSyncSeq(copyRepository.nextSyncSeq());
            photoRepository.save(photo);
            if (photo.getStorageKey() != null) {
                storageKeys.add(photo.getStorageKey());
            }
        }
        return orphans.size();
    }

    private Map<String, String> readClocks(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, CLOCKS);
    }
}
