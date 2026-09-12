package com.rekordo.services.social;

import com.rekordo.entity.SharingSettingsEntity;
import com.rekordo.model.core.Visibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single authority on who may see what.
 *
 * <p>Every screen, every endpoint and every image byte asks this class rather than
 * comparing settings itself. The rules are simple enough to be tempting to inline, and a
 * privacy rule inlined in six places is a privacy rule with six chances to be wrong.
 *
 * <p>The verdicts are computed live rather than stamped onto anything, which is what makes
 * closing a shelf take effect backwards as well as forwards.
 */
@Service
@RequiredArgsConstructor
public class VisibilityService {

    private final SharingService sharingService;
    private final FriendshipService friendshipService;

    /** A viewer's standing with one owner, which is all any of the rules below depend on. */
    public enum Standing {
        OWNER,
        FRIEND,
        STRANGER
    }

    @Transactional(readOnly = true)
    public Standing standingOf(UUID viewerId, UUID ownerId) {
        if (viewerId != null && viewerId.equals(ownerId)) {
            return Standing.OWNER;
        }
        return friendshipService.areFriends(viewerId, ownerId) ? Standing.FRIEND : Standing.STRANGER;
    }

    @Transactional(readOnly = true)
    public boolean canSeeCollection(UUID viewerId, UUID ownerId) {
        return allows(standingOf(viewerId, ownerId), settings(ownerId).getCollectionVisibility());
    }

    @Transactional(readOnly = true)
    public boolean canSeeWishlist(UUID viewerId, UUID ownerId) {
        return allows(standingOf(viewerId, ownerId), settings(ownerId).getWishlistVisibility());
    }

    /**
     * Whether what a copy cost rides along with it.
     *
     * <p>Two conditions, not one: the owner has to have turned prices on *and* the viewer
     * has to be allowed the collection in the first place. Sharing a shelf is not thereby
     * sharing what it cost.
     */
    @Transactional(readOnly = true)
    public boolean canSeePrices(UUID viewerId, UUID ownerId) {
        Standing standing = standingOf(viewerId, ownerId);
        if (standing == Standing.OWNER) {
            return true;
        }
        SharingSettingsEntity settings = settings(ownerId);
        return settings.isPricesPublic() && allows(standing, settings.getCollectionVisibility());
    }

    /**
     * Whether the grades show. Friends and up: a public page is a wall of sleeves, and
     * "VG+ / NM" is a detail for people the owner actually knows.
     */
    @Transactional(readOnly = true)
    public boolean canSeeGrades(UUID viewerId, UUID ownerId) {
        return standingOf(viewerId, ownerId) != Standing.STRANGER;
    }

    /**
     * Whether the stars show. Two conditions, like the prices: the owner has to have turned
     * ratings on *and* the viewer has to be allowed the collection. A rating is an opinion
     * rather than a description of the record, so it is shared on purpose or not at all.
     */
    @Transactional(readOnly = true)
    public boolean canSeeRatings(UUID viewerId, UUID ownerId) {
        Standing standing = standingOf(viewerId, ownerId);
        if (standing == Standing.OWNER) {
            return true;
        }
        SharingSettingsEntity settings = settings(ownerId);
        return settings.isRatingsShared() && allows(standing, settings.getCollectionVisibility());
    }

    private boolean allows(Standing standing, Visibility setting) {
        return switch (standing) {
            case OWNER -> true;
            case FRIEND -> setting != Visibility.ONLY_ME;
            case STRANGER -> setting == Visibility.PUBLIC;
        };
    }

    private SharingSettingsEntity settings(UUID ownerId) {
        return sharingService.settingsFor(ownerId);
    }
}
