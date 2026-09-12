package com.rekordo.services.social;

import com.rekordo.entity.SharingSettingsEntity;
import com.rekordo.model.core.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The privacy matrix. Every rule in the app funnels through this class, so the table below
 * is the whole specification — if a combination is not asserted here, nothing else asserts
 * it either.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisibilityServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    @Mock private SharingService sharingService;
    @Mock private FriendshipService friendshipService;

    @InjectMocks private VisibilityService service;

    private void owner(Visibility collection, Visibility wishlist, boolean prices) {
        owner(collection, wishlist, prices, false);
    }

    private void owner(Visibility collection, Visibility wishlist, boolean prices, boolean ratings) {
        SharingSettingsEntity settings = SharingSettingsEntity.defaultsFor(OWNER);
        settings.setCollectionVisibility(collection);
        settings.setWishlistVisibility(wishlist);
        settings.setPricesPublic(prices);
        settings.setRatingsShared(ratings);
        when(sharingService.settingsFor(OWNER)).thenReturn(settings);
        when(friendshipService.areFriends(FRIEND, OWNER)).thenReturn(true);
        when(friendshipService.areFriends(STRANGER, OWNER)).thenReturn(false);
        when(friendshipService.areFriends(null, OWNER)).thenReturn(false);
    }

    @Test
    void ownerSeesEverythingWhateverTheSettingsSay() {
        owner(Visibility.ONLY_ME, Visibility.ONLY_ME, false);

        assertThat(service.canSeeCollection(OWNER, OWNER)).isTrue();
        assertThat(service.canSeeWishlist(OWNER, OWNER)).isTrue();
        assertThat(service.canSeePrices(OWNER, OWNER)).isTrue();
        assertThat(service.canSeeGrades(OWNER, OWNER)).isTrue();
        assertThat(service.canSeeRatings(OWNER, OWNER)).isTrue();
    }

    @Test
    void onlyMeClosesTheShelfToFriendsToo() {
        owner(Visibility.ONLY_ME, Visibility.FRIENDS, false);

        assertThat(service.canSeeCollection(FRIEND, OWNER)).isFalse();
        // The lists are separate answers: closing one must not close the other.
        assertThat(service.canSeeWishlist(FRIEND, OWNER)).isTrue();
    }

    @Test
    void friendsMeansFriendsAndNotStrangers() {
        owner(Visibility.FRIENDS, Visibility.FRIENDS, false);

        assertThat(service.canSeeCollection(FRIEND, OWNER)).isTrue();
        assertThat(service.canSeeCollection(STRANGER, OWNER)).isFalse();
        assertThat(service.canSeeCollection(null, OWNER)).isFalse();
    }

    @Test
    void publicReachesSomebodyWithNoAccountAtAll() {
        owner(Visibility.PUBLIC, Visibility.PUBLIC, false);

        assertThat(service.canSeeCollection(null, OWNER)).isTrue();
        assertThat(service.canSeeWishlist(null, OWNER)).isTrue();
    }

    @Test
    void aPublicWishlistOverAClosedShelfIsTheNormalCase() {
        owner(Visibility.FRIENDS, Visibility.PUBLIC, false);

        assertThat(service.canSeeCollection(STRANGER, OWNER)).isFalse();
        assertThat(service.canSeeWishlist(STRANGER, OWNER)).isTrue();
    }

    @Test
    void pricesStayHiddenEvenOnAPublicShelfUntilTheOwnerTurnsThemOn() {
        owner(Visibility.PUBLIC, Visibility.PUBLIC, false);

        assertThat(service.canSeeCollection(STRANGER, OWNER)).isTrue();
        assertThat(service.canSeePrices(STRANGER, OWNER)).isFalse();
        assertThat(service.canSeePrices(FRIEND, OWNER)).isFalse();
    }

    @Test
    void pricesNeedBothTheToggleAndAccessToTheCollection() {
        // Prices on, but the shelf itself is shut. Turning prices on is not a way to leak
        // what a closed collection cost.
        owner(Visibility.ONLY_ME, Visibility.PUBLIC, true);

        assertThat(service.canSeePrices(FRIEND, OWNER)).isFalse();
        assertThat(service.canSeePrices(STRANGER, OWNER)).isFalse();
    }

    @Test
    void pricesReachWhoeverTheCollectionReachesOnceTheyAreOn() {
        owner(Visibility.FRIENDS, Visibility.FRIENDS, true);

        assertThat(service.canSeePrices(FRIEND, OWNER)).isTrue();
        assertThat(service.canSeePrices(STRANGER, OWNER)).isFalse();
    }

    @Test
    void ratingsStayHiddenEvenOnAPublicShelfUntilTheOwnerTurnsThemOn() {
        owner(Visibility.PUBLIC, Visibility.PUBLIC, true, false);

        assertThat(service.canSeeCollection(STRANGER, OWNER)).isTrue();
        // Prices on, ratings off: the two switches are separate answers.
        assertThat(service.canSeeRatings(FRIEND, OWNER)).isFalse();
        assertThat(service.canSeeRatings(STRANGER, OWNER)).isFalse();
    }

    @Test
    void ratingsNeedBothTheToggleAndAccessToTheCollection() {
        // Ratings on, shelf shut. Turning the stars on does not open the shelf.
        owner(Visibility.ONLY_ME, Visibility.PUBLIC, false, true);

        assertThat(service.canSeeRatings(FRIEND, OWNER)).isFalse();
        assertThat(service.canSeeRatings(STRANGER, OWNER)).isFalse();
    }

    @Test
    void ratingsReachWhoeverTheCollectionReachesOnceTheyAreOn() {
        owner(Visibility.FRIENDS, Visibility.FRIENDS, false, true);

        assertThat(service.canSeeRatings(FRIEND, OWNER)).isTrue();
        assertThat(service.canSeeRatings(STRANGER, OWNER)).isFalse();
        assertThat(service.canSeeRatings(null, OWNER)).isFalse();
    }

    @Test
    void ratingsOnAPublicShelfReachAnyone() {
        // Unlike the grades, which stop at the people the owner knows: the stars are shared
        // on purpose, so the owner has already answered this question by turning them on.
        owner(Visibility.PUBLIC, Visibility.PUBLIC, false, true);

        assertThat(service.canSeeRatings(STRANGER, OWNER)).isTrue();
    }

    @Test
    void gradesAreForPeopleTheOwnerActuallyKnows() {
        owner(Visibility.PUBLIC, Visibility.PUBLIC, true);

        assertThat(service.canSeeGrades(FRIEND, OWNER)).isTrue();
        // A public page is a wall of sleeves; VG+/NM is not part of it.
        assertThat(service.canSeeGrades(STRANGER, OWNER)).isFalse();
        assertThat(service.canSeeGrades(null, OWNER)).isFalse();
    }
}
