package com.rekordo.services.storage;

import com.rekordo.entity.PhotoEntity;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrphanPhotoSweeperTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final TypeReference<Map<String, String>> CLOCKS = new TypeReference<>() {};

    @Mock private PhotoRepository photoRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private StorageService storageService;
    @Mock private TransactionTemplate transactions;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OrphanPhotoSweeper sweeper;
    private long seq;

    @BeforeEach
    void setUp() {
        sweeper = new OrphanPhotoSweeper(
                photoRepository,
                copyRepository,
                storageService,
                objectMapper,
                transactions,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        seq = 100;
        when(copyRepository.nextSyncSeq()).thenAnswer(call -> ++seq);
        when(transactions.execute(any())).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
    }

    private PhotoEntity photo(String storageKey) {
        PhotoEntity photo = new PhotoEntity();
        photo.setId(UUID.randomUUID());
        photo.setUserId(UUID.randomUUID());
        photo.setCopyId(UUID.randomUUID());
        photo.setStorageKey(storageKey);
        photo.setContentType("image/jpeg");
        photo.setByteSize(1200L);
        photo.setSortIndex(0);
        photo.setCreatedAt(1000L);
        photo.setFieldClocks("{\"deletedAt\":\"000000000001000:0000:phone\",\"sortIndex\":\"000000000001000:0000:phone\"}");
        photo.setSyncSeq(1L);
        return photo;
    }

    @Test
    void asksOnlyForOwnersRemovedMoreThanTheGraceAgo() {
        when(photoRepository.findOrphaned(anyLong(), anyInt())).thenReturn(List.of());

        sweeper.sweep();

        verify(photoRepository).findOrphaned(eq(NOW - OrphanPhotoSweeper.GRACE_MS), eq(OrphanPhotoSweeper.BATCH));
    }

    @Test
    void tombstonesLikeADeviceWouldSoTheDeleteSurvivesTheNextMerge() {
        PhotoEntity orphan = photo("user/photo-1");
        when(photoRepository.findOrphaned(anyLong(), anyInt())).thenReturn(List.of(orphan));

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(orphan.getDeletedAt()).isEqualTo(NOW);
        Map<String, String> clocks = objectMapper.readValue(orphan.getFieldClocks(), CLOCKS);
        // A fresh stamp in the clients' encoding, newer than anything a device wrote before.
        assertThat(clocks.get("deletedAt")).isEqualTo("00" + NOW + ":0000:server-orphan-sweep");
        assertThat(clocks.get("deletedAt")).isGreaterThan("000000000001000:0000:phone");
        // The other fields keep their own clocks.
        assertThat(clocks.get("sortIndex")).isEqualTo("000000000001000:0000:phone");
        // A moved sync_seq is what makes devices pull the tombstone at all.
        assertThat(orphan.getSyncSeq()).isEqualTo(101L);
        verify(photoRepository).save(orphan);
    }

    @Test
    void removesTheBytesOnlyAfterTheTombstonesAreWritten() {
        PhotoEntity orphan = photo("user/photo-1");
        when(photoRepository.findOrphaned(anyLong(), anyInt())).thenReturn(List.of(orphan));

        sweeper.sweep();

        InOrder order = inOrder(transactions, storageService);
        order.verify(transactions).execute(any());
        order.verify(storageService).delete("user/photo-1");
    }

    @Test
    void aRowThatNeverHeldBytesIsTombstonedWithNothingToDelete() {
        when(photoRepository.findOrphaned(anyLong(), anyInt())).thenReturn(List.of(photo(null)));

        sweeper.sweep();

        verify(storageService, never()).delete(any());
    }

    @Test
    void keepsGoingWhileBatchesComeBackFull() {
        List<PhotoEntity> full = new ArrayList<>();
        IntStream.range(0, OrphanPhotoSweeper.BATCH).forEach(i -> full.add(photo("k" + i)));
        when(photoRepository.findOrphaned(anyLong(), anyInt()))
                .thenReturn(full)
                .thenReturn(List.of(photo("last")));

        assertThat(sweeper.sweep()).isEqualTo(OrphanPhotoSweeper.BATCH + 1);
        verify(photoRepository, times(2)).findOrphaned(anyLong(), anyInt());
    }
}
