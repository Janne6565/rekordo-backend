package com.rekordo.services.social;

import com.rekordo.entity.ActivityEventEntity;
import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.ActivityEntryDto;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.ActivityType;
import com.rekordo.model.core.CopyOrigin;
import com.rekordo.repository.ActivityEventRepository;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.ReleaseGroupRepository;
import com.rekordo.repository.ReleaseRepository;
import com.rekordo.repository.UserRepository;
import com.rekordo.repository.WishlistItemRepository;
import com.rekordo.services.metadata.MetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityServiceTest {

    private static final UUID VIEWER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    @Mock private ActivityEventRepository activityRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private ReleaseRepository releaseRepository;
    @Mock private ReleaseGroupRepository releaseGroupRepository;
    @Mock private WishlistItemRepository wishlistItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private VisibilityService visibilityService;
    @Mock private MetadataService metadataService;

    @InjectMocks private ActivityService service;

    @BeforeEach
    void setUp() {
        UserEntity friend = new UserEntity();
        friend.setId(FRIEND);
        friend.setHandle("friedrich.k");
        when(userRepository.findAllByIdIn(any())).thenReturn(List.of(friend));
        when(visibilityService.canSeeCollection(VIEWER, FRIEND)).thenReturn(true);
        when(visibilityService.canSeeWishlist(VIEWER, FRIEND)).thenReturn(true);
        when(copyRepository.findById(any())).thenAnswer(call -> {
            CopyEntity copy = new CopyEntity();
            copy.setId(call.getArgument(0));
            return Optional.of(copy);
        });
    }

    @ParameterizedTest
    @EnumSource(value = CopyOrigin.class, names = {"CSV_IMPORT", "FIRST_SYNC"})
    void saysNothingAboutCopiesThatArrivedInABatch(CopyOrigin origin) {
        service.recordCopyAdded(FRIEND, UUID.randomUUID(), origin, "musicbrainz:r1", null, "Aja", "Steely Dan", 1L);

        verify(activityRepository, never()).save(any());
    }

    @Test
    void saysNothingWhenTheClientDidNotSayWhyTheCopyExists() {
        // Silence is the safe failure mode: a client too old to send an origin must not be
        // able to announce somebody's entire collection.
        service.recordCopyAdded(FRIEND, UUID.randomUUID(), null, "musicbrainz:r1", null, "Aja", "Steely Dan", 1L);

        verify(activityRepository, never()).save(any());
    }

    @Test
    void announcesACopySomebodyAddedByHand() {
        service.recordCopyAdded(FRIEND, UUID.randomUUID(), CopyOrigin.MANUAL, "local:x", null, "Aja", "Steely Dan", 1L);

        assertThat(saved().getType()).isEqualTo(ActivityType.COPY_ADDED);
    }

    @Test
    void keepsTheDevicesOwnTimeEvenWhenItIsDaysBehind() {
        // A copy added on a plane and synced two days later belongs where the person put it.
        Instant twoDaysAgo = Instant.now().minus(Duration.ofDays(2));
        service.recordCopyAdded(
                FRIEND, UUID.randomUUID(), CopyOrigin.MANUAL, "local:x", null, "Aja", "Steely Dan", twoDaysAgo.toEpochMilli());

        assertThat(saved().getOccurredAt()).isCloseTo(twoDaysAgo, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void refusesToLetADeviceDateItsAddInTheFuture() {
        // Otherwise one badly-set clock pins a line to the top of every friend's feed forever.
        Instant nextYear = Instant.now().plus(Duration.ofDays(365));
        service.recordCopyAdded(
                FRIEND, UUID.randomUUID(), CopyOrigin.MANUAL, "local:x", null, "Aja", "Steely Dan", nextYear.toEpochMilli());

        assertThat(saved().getOccurredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void callsItAFindFromTheCopysOwnAlbumWithoutResolvingAPressing() {
        // A copy whose owner never chose a pressing has no release to walk up from, and one
        // that did should not need the mirror to have cached it. Both ask the album directly.
        when(wishlistItemRepository.findAllByUserIdAndAlbumIdAndDeletedAtIsNull(FRIEND, "musicbrainz:g1"))
                .thenReturn(List.of(new com.rekordo.entity.WishlistItemEntity()));

        service.recordCopyAdded(
                FRIEND, UUID.randomUUID(), CopyOrigin.MANUAL, null, "musicbrainz:g1", "Aja", "Steely Dan", 1L);

        assertThat(saved()).satisfies(event -> {
            assertThat(event.getType()).isEqualTo(ActivityType.WISH_FULFILLED);
            // The feed draws its record through getReleases, which answers for an album id,
            // so the album stands in for the pressing nobody picked.
            assertThat(event.getReleaseId()).isEqualTo("musicbrainz:g1");
        });
        verifyNoInteractions(releaseRepository);
    }

    @Test
    void callsItAFindWhenTheAlbumWasOnTheirWishlist() {
        com.rekordo.entity.ReleaseEntity release = new com.rekordo.entity.ReleaseEntity();
        release.setExternalId("musicbrainz:r1");
        release.setReleaseGroupId(UUID.randomUUID());
        release.setTitle("Bitches Brew");
        release.setArtistName("Miles Davis");
        com.rekordo.entity.ReleaseGroupEntity group = new com.rekordo.entity.ReleaseGroupEntity();
        group.setExternalId("musicbrainz:g1");
        when(releaseRepository.findByExternalId("musicbrainz:r1")).thenReturn(Optional.of(release));
        when(releaseGroupRepository.findById(any())).thenReturn(Optional.of(group));
        when(wishlistItemRepository.findAllByUserIdAndAlbumIdAndDeletedAtIsNull(FRIEND, "musicbrainz:g1"))
                .thenReturn(List.of(new com.rekordo.entity.WishlistItemEntity()));

        service.recordCopyAdded(FRIEND, UUID.randomUUID(), CopyOrigin.MANUAL, "musicbrainz:r1", null, null, null, 1L);

        assertThat(saved()).satisfies(event -> {
            assertThat(event.getType()).isEqualTo(ActivityType.WISH_FULFILLED);
            // The title comes from the mirror for a matched copy, and is stored rather than
            // re-resolved: the mirror is a cache anybody may drop.
            assertThat(event.getTitle()).isEqualTo("Bitches Brew");
        });
    }

    @Test
    void foldsABurstOfAddsByOnePersonIntoOneLine() {
        Instant now = Instant.now();
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(
                event(ActivityType.COPY_ADDED, "Remain in Light", now),
                event(ActivityType.COPY_ADDED, "Fear of Music", now.minus(Duration.ofMinutes(3))),
                event(ActivityType.COPY_ADDED, "Speaking in Tongues", now.minus(Duration.ofMinutes(6)))));

        List<ActivityEntryDto> entries = service.feed(VIEWER, List.of(FRIEND)).entries();

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.copyCount()).isEqualTo(3);
            assertThat(entry.title()).isEqualTo("Remain in Light");
            assertThat(entry.collapsedCovers()).isNotNull();
        });
    }

    @Test
    void keepsAddsOnDifferentDaysApart() {
        Instant now = Instant.now();
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(
                event(ActivityType.COPY_ADDED, "Remain in Light", now),
                event(ActivityType.COPY_ADDED, "Solid Air", now.minus(Duration.ofHours(26)))));

        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries())
                .extracting(ActivityEntryDto::copyCount)
                .containsExactly(1, 1);
    }

    @Test
    void dropsEveryLineAboutAShelfThatHasSinceClosed() {
        when(visibilityService.canSeeCollection(VIEWER, FRIEND)).thenReturn(false);
        when(activityRepository.feedFor(any(), any()))
                .thenReturn(List.of(event(ActivityType.COPY_ADDED, "Remain in Light", Instant.now())));

        // Applied on the way out, so "Only me" reaches backwards without anything having to
        // be found and deleted from other people's feeds.
        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries()).isEmpty();
    }

    @Test
    void withholdsALineAboutACopyThatHasSinceBeenHidden() {
        CopyEntity hidden = new CopyEntity();
        hidden.setHidden(true);
        when(copyRepository.findById(any())).thenReturn(Optional.of(hidden));
        when(activityRepository.feedFor(any(), any()))
                .thenReturn(List.of(event(ActivityType.COPY_ADDED, "Remain in Light", Instant.now())));

        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries()).isEmpty();
    }

    @Test
    void showsAnAcceptedRequestOnlyToThePersonWhoAsked() {
        ActivityEventEntity accepted = event(ActivityType.FRIENDSHIP_ACCEPTED, null, Instant.now());
        accepted.setSubjectId(UUID.randomUUID());
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(accepted));

        // Somebody else accepting a request is not activity their other friends need.
        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries()).isEmpty();

        accepted.setSubjectId(VIEWER);
        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries()).hasSize(1);
    }

    @Test
    void keepsAnAcceptedRequestOutOfTheAcceptersOwnFeed() {
        // The line is written from the actor - "<name> accepted your request" - so showing
        // it to the accepter read as the viewer's own name accepting their own request.
        ActivityEventEntity accepted = event(ActivityType.FRIENDSHIP_ACCEPTED, null, Instant.now());
        accepted.setActorId(VIEWER);
        accepted.setSubjectId(FRIEND);
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(accepted));

        assertThat(service.feed(VIEWER, List.of(FRIEND)).entries()).isEmpty();
    }

    @Test
    void hasNothingToShowSomebodyWithNoFriendsYet() {
        assertThat(service.feed(VIEWER, List.of()).entries()).isEmpty();
        verify(activityRepository, never()).feedFor(any(), any());
    }

    private ActivityEventEntity event(ActivityType type, String title, Instant occurredAt) {
        ActivityEventEntity entity = new ActivityEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setActorId(FRIEND);
        entity.setType(type);
        entity.setSubjectId(UUID.randomUUID());
        entity.setTitle(title);
        entity.setOccurredAt(occurredAt);
        entity.setRecordedAt(occurredAt);
        return entity;
    }

    @Test
    void findsTheCoverForAWishByLookingTheAlbumUpRatherThanThePressing() {
        /*
         * A wish line stores an *album* id where every other type stores a pressing's, and
         * the release mirror is keyed by pressing. Looking one up in the other never
         * matched, so every "is looking for" line in the feed drew a blank tile.
         */
        ActivityEventEntity wish = event(ActivityType.WISH_ADDED, "Hadestown", Instant.now());
        wish.setReleaseId("musicbrainz:g-hadestown");
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(wish));
        when(metadataService.mirroredAlbumCovers(any()))
                .thenReturn(Map.of("musicbrainz:g-hadestown", "https://covers.example/hadestown.jpg"));

        ActivityEntryDto entry = service.feed(VIEWER, List.of(FRIEND)).entries().getFirst();

        assertThat(entry.coverArtUrl()).isEqualTo("https://covers.example/hadestown.jpg");
    }

    @Test
    void drawsAWishInTheFormatItIsBeingHuntedIn() {
        // An album has no format -- the wish does. Without it the line could say what
        // somebody was looking for but not draw the thing they were looking for.
        ActivityEventEntity wish = event(ActivityType.WISH_ADDED, "Hadestown", Instant.now());
        wish.setWantedFormat("CASSETTE");
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(wish));

        ActivityEntryDto entry = service.feed(VIEWER, List.of(FRIEND)).entries().getFirst();

        assertThat(entry.format()).isEqualTo(Format.CASSETTE);
    }

    @Test
    void leavesTheFormatOffAWishThatWantsAny() {
        ActivityEventEntity wish = event(ActivityType.WISH_ADDED, "Hadestown", Instant.now());
        wish.setWantedFormat(null);
        when(activityRepository.feedFor(any(), any())).thenReturn(List.of(wish));

        ActivityEntryDto entry = service.feed(VIEWER, List.of(FRIEND)).entries().getFirst();

        assertThat(entry.format()).isNull();
    }

    @Test
    void keepsTheWantedFormatOnTheLineItRecords() {
        service.recordWishAdded(
                FRIEND, UUID.randomUUID(), "musicbrainz:g1", "Hadestown", "Ana\u00efs Mitchell", "VINYL", 1L);

        assertThat(saved().getWantedFormat()).isEqualTo("VINYL");
    }

    private ActivityEventEntity saved() {
        ArgumentCaptor<ActivityEventEntity> captor = ArgumentCaptor.forClass(ActivityEventEntity.class);
        verify(activityRepository).save(captor.capture());
        return captor.getValue();
    }
}
