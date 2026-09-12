package com.rekordo.services.social;

import com.rekordo.entity.CopyEntity;
import com.rekordo.entity.PhotoEntity;
import com.rekordo.entity.FriendshipEntity;
import com.rekordo.entity.ReleaseEntity;
import com.rekordo.entity.UserEntity;
import com.rekordo.entity.WishlistItemEntity;
import com.rekordo.model.core.Format;
import com.rekordo.model.core.FriendRequestDto;
import com.rekordo.model.core.FriendsOverviewDto;
import com.rekordo.model.core.ProfileDto;
import com.rekordo.model.core.ProfileSummaryDto;
import com.rekordo.model.core.RelationshipDto;
import com.rekordo.model.core.ReleaseDto;
import com.rekordo.model.core.SharedCollectionDto;
import com.rekordo.model.core.SharedCopyDto;
import com.rekordo.model.core.SharedWishDto;
import com.rekordo.model.core.SharedWishlistDto;
import com.rekordo.model.exception.ProfileNotFoundException;
import com.rekordo.model.exception.ProfileNotVisibleException;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.PhotoRepository;
import com.rekordo.repository.ReleaseRepository;
import com.rekordo.repository.UserRepository;
import com.rekordo.repository.WishlistItemRepository;
import com.rekordo.services.metadata.MetadataMapper;
import com.rekordo.services.storage.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Somebody else's shelf, assembled for one particular viewer.
 *
 * <p>Every list this service returns has already had {@link VisibilityService} applied to
 * it. Nothing here trusts a flag the client sent, and nothing returns a field the viewer
 * has not earned — a copy the viewer may not price comes back with a null price rather than
 * a price the UI is asked politely not to draw.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    /** Shortest prefix the handle autocomplete will act on. */
    private static final int MIN_QUERY_LENGTH = 3;

    private static final int SEARCH_LIMIT = 20;

    /**
     * How much of one shelf comes back at once. Generous enough that the format counts the
     * client derives under the grid are right for any real collection, and the response
     * says when it was not.
     */
    private static final int LIST_LIMIT = 1000;

    private final UserRepository userRepository;
    private final CopyRepository copyRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final PhotoRepository photoRepository;
    private final ReleaseRepository releaseRepository;
    private final FriendshipService friendshipService;
    private final VisibilityService visibilityService;

    /**
     * Handle autocomplete.
     *
     * <p>Open to signed-out visitors on purpose — someone handed a handle should be able to
     * look at the shelf before deciding whether the app is worth an account. What keeps it
     * from being a directory is the shape of the query, not a login: three characters
     * minimum, prefix only, twenty results, and only over people who have not opted out.
     */
    @Transactional(readOnly = true)
    public List<ProfileSummaryDto> search(UUID viewerId, String query) {
        String prefix = normalise(query);
        if (prefix.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        List<UserEntity> found =
                userRepository.searchByHandlePrefix(escapeLike(prefix), PageRequest.of(0, SEARCH_LIMIT));
        return found.stream().map(user -> summaryOf(viewerId, user)).toList();
    }

    @Transactional(readOnly = true)
    public ProfileDto profile(UUID viewerId, String handle) {
        UserEntity owner = require(handle);
        boolean collection = visibilityService.canSeeCollection(viewerId, owner.getId());
        return new ProfileDto(
                owner.getId(),
                owner.getHandle(),
                owner.getDisplayName(),
                AvatarService.urlFor(owner),
                friendshipService.relationship(viewerId, owner.getId()),
                friendshipService.incomingRequestId(viewerId, owner.getId()).orElse(null),
                collection,
                visibilityService.canSeeWishlist(viewerId, owner.getId()),
                visibilityService.canSeePrices(viewerId, owner.getId()),
                // Said out loud even behind a lock: 15d's whole invitation is the number.
                copyRepository.countVisible(owner.getId()),
                wishlistItemRepository.countVisible(owner.getId()),
                owner.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public SharedCollectionDto collection(UUID viewerId, String handle) {
        UserEntity owner = require(handle);
        if (!visibilityService.canSeeCollection(viewerId, owner.getId())) {
            throw new ProfileNotVisibleException(owner.getHandle());
        }
        boolean prices = visibilityService.canSeePrices(viewerId, owner.getId());
        boolean grades = visibilityService.canSeeGrades(viewerId, owner.getId());
        boolean ratings = visibilityService.canSeeRatings(viewerId, owner.getId());

        List<CopyEntity> copies = copyRepository.findVisible(owner.getId(), PageRequest.of(0, LIST_LIMIT + 1));
        boolean truncated = copies.size() > LIST_LIMIT;
        List<CopyEntity> shown = truncated ? copies.subList(0, LIST_LIMIT) : copies;

        Map<String, ReleaseDto> releases = resolve(shown);
        Map<UUID, UUID> previews = previewPhotos(owner.getId(), shown);
        List<SharedCopyDto> dtos = new ArrayList<>(shown.size());
        for (CopyEntity copy : shown) {
            dtos.add(toDto(
                    copy,
                    releases.get(catalogueKeyOf(copy)),
                    previews.get(copy.getId()),
                    prices,
                    grades,
                    ratings));
        }
        return new SharedCollectionDto(dtos, copyRepository.countVisible(owner.getId()), truncated);
    }

    @Transactional(readOnly = true)
    public SharedWishlistDto wishlist(UUID viewerId, String handle) {
        UserEntity owner = require(handle);
        if (!visibilityService.canSeeWishlist(viewerId, owner.getId())) {
            throw new ProfileNotVisibleException(owner.getHandle());
        }
        List<WishlistItemEntity> wishes =
                wishlistItemRepository.findVisible(owner.getId(), PageRequest.of(0, LIST_LIMIT + 1));
        boolean truncated = wishes.size() > LIST_LIMIT;
        List<WishlistItemEntity> shown = truncated ? wishes.subList(0, LIST_LIMIT) : wishes;

        List<SharedWishDto> dtos = shown.stream()
                .map(wish -> new SharedWishDto(
                        wish.getId().toString(),
                        wish.getAlbumId(),
                        wish.getReleaseId(),
                        wish.getTitle(),
                        wish.getArtistName(),
                        wish.getYear(),
                        wish.getDesiredFormat(),
                        wish.getCreatedAt()))
                .toList();
        return new SharedWishlistDto(dtos, wishlistItemRepository.countVisible(owner.getId()), truncated);
    }

    /** People, requests and outgoing asks in one response — the whole People panel of 15b. */
    @Transactional(readOnly = true)
    public FriendsOverviewDto friendsOverview(UUID viewerId) {
        List<UUID> friendIds = friendshipService.friendIds(viewerId);
        Map<UUID, UserEntity> people = byId(friendIds);

        List<ProfileSummaryDto> friends = friendIds.stream()
                .map(people::get)
                .filter(user -> user != null)
                .map(user -> summaryOf(viewerId, user))
                .toList();

        List<FriendRequestDto> incoming = new ArrayList<>();
        for (FriendshipEntity request : friendshipService.incoming(viewerId)) {
            userRepository.findById(request.getRequesterId()).ifPresent(from -> incoming.add(new FriendRequestDto(
                    request.getId(),
                    summaryOf(viewerId, from),
                    request.getCreatedAt(),
                    friendshipService.mutualFriendCount(viewerId, from.getId()))));
        }

        List<ProfileSummaryDto> outgoing = friendshipService.outgoing(viewerId).stream()
                .map(request -> userRepository.findById(request.getAddresseeId()).orElse(null))
                .filter(user -> user != null)
                .map(user -> summaryOf(viewerId, user))
                .toList();

        return new FriendsOverviewDto(friends, incoming, outgoing);
    }

    /**
     * One person in a list.
     *
     * <p>The copy count is withheld when the shelf is closed to this viewer. "312 copies"
     * under a locked profile is a fact about a collection somebody chose not to show, and a
     * search result is not the place to leak it — the profile screen names a total only
     * because the design deliberately makes that the invitation to ask.
     */
    private ProfileSummaryDto summaryOf(UUID viewerId, UserEntity user) {
        boolean visible = visibilityService.canSeeCollection(viewerId, user.getId());
        return new ProfileSummaryDto(
                user.getId(),
                user.getHandle(),
                user.getDisplayName(),
                AvatarService.urlFor(user),
                visible ? copyRepository.countVisible(user.getId()) : null,
                friendshipService.relationship(viewerId, user.getId()),
                !visible);
    }

    /**
     * The release behind each copy.
     *
     * <p>Copies typed in by hand carry their own release facts and point at
     * {@code local:<their own id>}, which is in no mirror and never will be, so they are
     * derived here rather than looked up.
     */
    private Map<String, ReleaseDto> resolve(List<CopyEntity> copies) {
        List<String> ids = copies.stream()
                .map(ProfileService::catalogueKeyOf)
                .filter(id -> id != null && !id.startsWith("local:"))
                .distinct()
                .toList();
        Map<String, ReleaseDto> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ReleaseEntity release : releaseRepository.findAllByExternalIdIn(ids)) {
                byId.put(release.getExternalId(), MetadataMapper.toDto(release, null));
            }
        }
        return byId;
    }

    /**
     * The first photo of each copy, which is the picture that stands for it.
     *
     * The same rule as {@code copyPreviewSrc} on the clients: the copy's own first photo
     * wins unless it has starred the catalogue artwork instead. Resolved here rather than
     * left to the viewer's device, because the viewer has none of the owner's photos and
     * cannot ask the strip which one is first.
     */
    private Map<UUID, UUID> previewPhotos(UUID ownerId, List<CopyEntity> copies) {
        List<UUID> ids = copies.stream()
                .filter(copy -> !PREFERRED_CATALOG_ART.equalsIgnoreCase(copy.getCatalogArt()))
                .map(CopyEntity::getId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> first = new HashMap<>();
        // Ordered by sortIndex, so the first row seen for a copy is the one the strip shows.
        for (PhotoEntity photo : photoRepository.findVisibleForCopies(ownerId, ids)) {
            first.putIfAbsent(photo.getCopyId(), photo.getId());
        }
        return first;
    }

    private SharedCopyDto toDto(
            CopyEntity copy,
            ReleaseDto release,
            UUID previewPhotoId,
            boolean prices,
            boolean grades,
            boolean ratings) {
        String title = firstNonBlank(copy.getManualTitle(), release == null ? null : release.title(), "Untitled");
        String artist =
                firstNonBlank(copy.getManualArtist(), release == null ? null : release.artistName(), "Unknown artist");
        Integer year = copy.getManualYear() != null ? copy.getManualYear() : release == null ? null : release.year();
        return new SharedCopyDto(
                copy.getId().toString(),
                copy.getReleaseId(),
                title,
                artist,
                year,
                format(copy, release),
                // `catalogArtShown`, server-side: a copy that took the archive's artwork out
                // of its own images has done so everywhere, not only on the owner's device.
                HIDDEN_CATALOG_ART.equalsIgnoreCase(copy.getCatalogArt()) || release == null
                        ? null
                        : release.coverArtUrl(),
                previewPhotoId == null ? null : previewPhotoId.toString(),
                release == null ? null : release.coverTheme(),
                grades ? copy.getCondition() : null,
                grades ? copy.getSleeveCondition() : null,
                ratings ? copy.getRating() : null,
                prices ? copy.getPricePaidCents() : null,
                prices ? copy.getCurrency() : null,
                copy.getCreatedAt());
    }

    /** Mirrors the shared package's CatalogArtChoice, which the entity stores as text. */
    private static final String PREFERRED_CATALOG_ART = "PREFERRED";

    private static final String HIDDEN_CATALOG_ART = "HIDDEN";

    /** The copy's own answer first, exactly as {@code copyFormat} does on the clients. */
    private Format format(CopyEntity copy, ReleaseDto release) {
        if (copy.getManualFormat() != null && !copy.getManualFormat().isBlank()) {
            try {
                return Format.valueOf(copy.getManualFormat().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return Format.OTHER;
            }
        }
        return release == null ? Format.OTHER : release.format();
    }

    private UserEntity require(String handle) {
        String normalised = normalise(handle);
        return userRepository
                .findByHandleIgnoreCase(normalised)
                .orElseThrow(() -> new ProfileNotFoundException(normalised));
    }

    private Map<UUID, UserEntity> byId(List<UUID> ids) {
        Map<UUID, UserEntity> people = new HashMap<>();
        if (ids.isEmpty()) {
            return people;
        }
        for (UserEntity user : userRepository.findAllByIdIn(ids)) {
            people.put(user.getId(), user);
        }
        return people;
    }

    private static String normalise(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }

    /**
     * A prefix is user input going into a LIKE pattern. Without this, typing a percent sign
     * would match every handle on the platform in one query.
     */
    private static String escapeLike(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * The key a copy's release facts live under: the pressing when one was chosen, the
     * album otherwise.
     *
     * <p>A copy whose owner never picked a pressing still has to draw on a friend's shelf,
     * and {@code MetadataService.getReleases} answers for an album id by describing the
     * album itself.
     */
    private static String catalogueKeyOf(CopyEntity copy) {
        return copy.getReleaseId() != null ? copy.getReleaseId() : copy.getAlbumId();
    }
}
