package com.rekordo.services.social;

import com.rekordo.entity.FriendshipEntity;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.FriendshipStatus;
import com.rekordo.model.core.RelationshipDto;
import com.rekordo.model.exception.AlreadyFriendsException;
import com.rekordo.model.exception.FriendRequestNotFoundException;
import com.rekordo.model.exception.HandleRequiredException;
import com.rekordo.model.exception.ProfileNotFoundException;
import com.rekordo.model.exception.SelfFriendshipException;
import com.rekordo.repository.CopyRepository;
import com.rekordo.repository.FriendshipRepository;
import com.rekordo.repository.UserRepository;
import com.rekordo.services.notifications.PushEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The friend graph. Requests, acceptance, and who is friends with whom — and nothing about
 * what any of them are allowed to see, which is {@link VisibilityService}'s job.
 *
 * <p>Friendship is mutual, so it is one row. Which side asked is still recorded, because
 * only the person who was asked may accept.
 */
@Service
@RequiredArgsConstructor
public class FriendshipService {

    private static final Logger log = LoggerFactory.getLogger(FriendshipService.class);

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final CopyRepository copyRepository;
    private final ApplicationEventPublisher events;

    /**
     * Ask somebody to be friends.
     *
     * <p>Addressed by handle because that is what the person typed. An account with no
     * handle of its own is refused: a request nobody can look up the sender of is a request
     * nobody can sensibly answer.
     */
    @Transactional
    public FriendshipEntity request(UserEntity viewer, String handle) {
        if (viewer.getHandle() == null) {
            throw new HandleRequiredException();
        }
        UserEntity target = userRepository
                .findByHandleIgnoreCase(stripAt(handle))
                .orElseThrow(() -> new ProfileNotFoundException(stripAt(handle)));
        if (target.getId().equals(viewer.getId())) {
            throw new SelfFriendshipException();
        }
        if (friendshipRepository.findBetween(viewer.getId(), target.getId()).isPresent()) {
            throw new AlreadyFriendsException(stripAt(handle));
        }

        FriendshipEntity friendship = new FriendshipEntity();
        friendship.setId(UUID.randomUUID());
        friendship.setRequesterId(viewer.getId());
        friendship.setAddresseeId(target.getId());
        friendship.setStatus(FriendshipStatus.PENDING);
        friendship.setCreatedAt(Instant.now());
        friendshipRepository.save(friendship);

        // Board 22c's one surviving per-event push: it names a person and waits for an
        // answer, which is the whole test it had to pass. Whether it actually buzzes is the
        // addressee's grid and their devices' mutes to decide, not this service's.
        events.publishEvent(new PushEvent.FriendRequested(
                target.getId(),
                viewer.getDisplayName() == null ? "@" + viewer.getHandle() : viewer.getDisplayName(),
                viewer.getHandle(),
                copyRepository.countVisible(viewer.getId())));

        log.debug("User {} asked to be friends with {}", viewer.getId(), target.getId());
        return friendship;
    }

    /**
     * Accept a request addressed to you, and return who asked.
     *
     * <p>The feed line about it is written by the caller rather than here. This service
     * knows the graph and nothing else — telling it about activity would make it depend on
     * a service that already depends on it, through visibility, to read the feed back.
     */
    @Transactional
    public UUID accept(UUID viewerId, UUID requestId) {
        FriendshipEntity friendship = pendingFor(viewerId, requestId);
        friendship.setStatus(FriendshipStatus.ACCEPTED);
        friendship.setRespondedAt(Instant.now());
        friendshipRepository.save(friendship);
        log.debug("User {} accepted friend request {}", viewerId, requestId);
        return friendship.getRequesterId();
    }

    /**
     * Turn a request down. The row is deleted rather than marked, so the person can ask
     * again — an accidental decline is a mis-tap, not a decision worth making permanent,
     * and nobody needs a durable record of having been refused.
     */
    @Transactional
    public void decline(UUID viewerId, UUID requestId) {
        friendshipRepository.delete(pendingFor(viewerId, requestId));
        log.debug("User {} declined friend request {}", viewerId, requestId);
    }

    /** Withdraw a request this account sent, or end a friendship. Both are the same delete. */
    @Transactional
    public void remove(UUID viewerId, UUID otherId) {
        friendshipRepository
                .findBetween(viewerId, otherId)
                .filter(friendship -> friendship.involves(viewerId))
                .ifPresent(friendship -> {
                    friendshipRepository.delete(friendship);
                    log.debug("User {} removed the friendship with {}", viewerId, otherId);
                });
    }

    @Transactional(readOnly = true)
    public RelationshipDto relationship(UUID viewerId, UUID otherId) {
        if (viewerId == null) {
            return RelationshipDto.ANONYMOUS;
        }
        if (viewerId.equals(otherId)) {
            return RelationshipDto.SELF;
        }
        return friendshipRepository
                .findBetween(viewerId, otherId)
                .map(friendship -> switch (friendship.getStatus()) {
                    case ACCEPTED -> RelationshipDto.FRIENDS;
                    case PENDING -> friendship.getRequesterId().equals(viewerId)
                            ? RelationshipDto.REQUEST_SENT
                            : RelationshipDto.REQUEST_RECEIVED;
                })
                .orElse(RelationshipDto.NONE);
    }

    /**
     * The id of the request the other person sent this viewer, when there is one.
     *
     * <p>A profile is looked up by handle, but accepting and declining name the request
     * itself — so without this the one screen that says "@janne2 asked to be friends" has
     * no way to answer it, and the button on it can only ask again.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> incomingRequestId(UUID viewerId, UUID otherId) {
        if (viewerId == null || viewerId.equals(otherId)) {
            return Optional.empty();
        }
        return friendshipRepository
                .findBetween(viewerId, otherId)
                .filter(friendship -> friendship.getStatus() == FriendshipStatus.PENDING)
                .filter(friendship -> friendship.getAddresseeId().equals(viewerId))
                .map(FriendshipEntity::getId);
    }

    @Transactional(readOnly = true)
    public boolean areFriends(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        return friendshipRepository
                .findBetween(a, b)
                .filter(friendship -> friendship.getStatus() == FriendshipStatus.ACCEPTED)
                .isPresent();
    }

    @Transactional(readOnly = true)
    public List<UUID> friendIds(UUID userId) {
        return userId == null ? List.of() : friendshipRepository.findFriendIds(userId);
    }

    /** The "4 friends in common" line on a request card. */
    @Transactional(readOnly = true)
    public long mutualFriendCount(UUID a, UUID b) {
        Set<UUID> mine = new HashSet<>(friendIds(a));
        return friendIds(b).stream().filter(mine::contains).count();
    }

    @Transactional(readOnly = true)
    public List<FriendshipEntity> incoming(UUID userId) {
        return friendshipRepository.findAllByAddresseeIdAndStatusOrderByCreatedAtDesc(
                userId, FriendshipStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<FriendshipEntity> outgoing(UUID userId) {
        return friendshipRepository.findAllByRequesterIdAndStatusOrderByCreatedAtDesc(
                userId, FriendshipStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public List<FriendshipEntity> accepted(UUID userId) {
        return friendshipRepository.findAllByUserAndStatus(userId, FriendshipStatus.ACCEPTED);
    }

    private FriendshipEntity pendingFor(UUID viewerId, UUID requestId) {
        Optional<FriendshipEntity> found = friendshipRepository.findById(requestId);
        return found.filter(friendship -> friendship.getStatus() == FriendshipStatus.PENDING)
                // Only the person who was asked may answer. Letting the requester accept
                // their own request would make friendship one-sided after all.
                .filter(friendship -> friendship.getAddresseeId().equals(viewerId))
                .orElseThrow(() -> new FriendRequestNotFoundException(requestId));
    }

    private static String stripAt(String handle) {
        String trimmed = handle == null ? "" : handle.trim();
        return trimmed.startsWith("@") ? trimmed.substring(1) : trimmed;
    }
}
