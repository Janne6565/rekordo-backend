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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FriendshipServiceTest {

    private static final UUID ASKER = UUID.randomUUID();
    private static final UUID ASKED = UUID.randomUUID();
    private static final UUID REQUEST = UUID.randomUUID();

    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserRepository userRepository;
    @Mock private CopyRepository copyRepository;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private FriendshipService service;

    private UserEntity asker;

    @BeforeEach
    void setUp() {
        asker = user(ASKER, "jonasmeyer");
        when(userRepository.findByHandleIgnoreCase("friedrich.k")).thenReturn(Optional.of(user(ASKED, "friedrich.k")));
        when(userRepository.findByHandleIgnoreCase("jonasmeyer")).thenReturn(Optional.of(asker));
        when(friendshipRepository.findBetween(any(), any())).thenReturn(Optional.empty());
    }

    private static UserEntity user(UUID id, String handle) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setHandle(handle);
        return user;
    }

    private FriendshipEntity pending(UUID requester, UUID addressee) {
        FriendshipEntity friendship = new FriendshipEntity();
        friendship.setId(REQUEST);
        friendship.setRequesterId(requester);
        friendship.setAddresseeId(addressee);
        friendship.setStatus(FriendshipStatus.PENDING);
        friendship.setCreatedAt(Instant.now());
        return friendship;
    }

    @Test
    void createsAPendingRequestPointingAtWhoWasAsked() {
        FriendshipEntity created = service.request(asker, "@friedrich.k");

        assertThat(created).satisfies(friendship -> {
            assertThat(friendship.getRequesterId()).isEqualTo(ASKER);
            assertThat(friendship.getAddresseeId()).isEqualTo(ASKED);
            assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        });
    }

    @Test
    void refusesToAskWithoutAHandleOfYourOwn() {
        asker.setHandle(null);

        // A request nobody can look the sender up by is one nobody can answer.
        assertThatThrownBy(() -> service.request(asker, "friedrich.k"))
                .isInstanceOf(HandleRequiredException.class);
    }

    @Test
    void refusesAHandleNobodyHolds() {
        assertThatThrownBy(() -> service.request(asker, "nobody"))
                .isInstanceOf(ProfileNotFoundException.class);
    }

    @Test
    void refusesToBefriendYourself() {
        assertThatThrownBy(() -> service.request(asker, "jonasmeyer"))
                .isInstanceOf(SelfFriendshipException.class);
    }

    @Test
    void refusesASecondRequestWhenTheOtherSideAlreadyAsked() {
        // Looked up unordered, so B asking A collides with A's open request rather than
        // creating a mirror row both of them could accept.
        when(friendshipRepository.findBetween(ASKER, ASKED)).thenReturn(Optional.of(pending(ASKED, ASKER)));

        assertThatThrownBy(() -> service.request(asker, "friedrich.k"))
                .isInstanceOf(AlreadyFriendsException.class);
    }

    @Test
    void onlyThePersonWhoWasAskedMayAccept() {
        when(friendshipRepository.findById(REQUEST)).thenReturn(Optional.of(pending(ASKER, ASKED)));

        // Accepting your own request would make friendship one-sided after all.
        assertThatThrownBy(() -> service.accept(ASKER, REQUEST))
                .isInstanceOf(FriendRequestNotFoundException.class);
        verify(friendshipRepository, never()).save(any());
    }

    @Test
    void acceptingStampsTheAnswerAndKeepsTheRow() {
        FriendshipEntity request = pending(ASKER, ASKED);
        when(friendshipRepository.findById(REQUEST)).thenReturn(Optional.of(request));

        service.accept(ASKED, REQUEST);

        assertThat(request.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
        assertThat(request.getRespondedAt()).isNotNull();
    }

    @Test
    void decliningDeletesTheRequestSoItCanBeSentAgain() {
        FriendshipEntity request = pending(ASKER, ASKED);
        when(friendshipRepository.findById(REQUEST)).thenReturn(Optional.of(request));

        service.decline(ASKED, REQUEST);

        // Nobody needs a durable record of having been refused, and a mis-tap has to be
        // undoable by the other person simply asking again.
        verify(friendshipRepository).delete(request);
    }

    @Test
    void answeringAnAlreadyAcceptedFriendshipIsNotFound() {
        FriendshipEntity accepted = pending(ASKER, ASKED);
        accepted.setStatus(FriendshipStatus.ACCEPTED);
        when(friendshipRepository.findById(REQUEST)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.accept(ASKED, REQUEST))
                .isInstanceOf(FriendRequestNotFoundException.class);
    }

    @Test
    void readsTheRelationshipFromWhicheverSideIsAsking() {
        // The query is unordered, so both directions find the same row.
        FriendshipEntity request = pending(ASKER, ASKED);
        when(friendshipRepository.findBetween(ASKER, ASKED)).thenReturn(Optional.of(request));
        when(friendshipRepository.findBetween(ASKED, ASKER)).thenReturn(Optional.of(request));

        assertThat(service.relationship(ASKER, ASKED)).isEqualTo(RelationshipDto.REQUEST_SENT);
        assertThat(service.relationship(ASKED, ASKER)).isEqualTo(RelationshipDto.REQUEST_RECEIVED);
    }

    @Test
    void handsTheRequestIdOnlyToThePersonWhoWasAsked() {
        FriendshipEntity request = pending(ASKER, ASKED);
        when(friendshipRepository.findBetween(ASKER, ASKED)).thenReturn(Optional.of(request));
        when(friendshipRepository.findBetween(ASKED, ASKER)).thenReturn(Optional.of(request));

        // The one who was asked can answer it; the one who asked has nothing to answer.
        assertThat(service.incomingRequestId(ASKED, ASKER)).contains(REQUEST);
        assertThat(service.incomingRequestId(ASKER, ASKED)).isEmpty();
    }

    @Test
    void hasNoRequestToAnswerOnceItIsAccepted() {
        FriendshipEntity accepted = pending(ASKER, ASKED);
        accepted.setStatus(FriendshipStatus.ACCEPTED);
        when(friendshipRepository.findBetween(ASKED, ASKER)).thenReturn(Optional.of(accepted));

        assertThat(service.incomingRequestId(ASKED, ASKER)).isEmpty();
    }

    @Test
    void hasNoRelationshipToOfferSomebodyWhoIsNotSignedIn() {
        assertThat(service.relationship(null, ASKED)).isEqualTo(RelationshipDto.ANONYMOUS);
    }

    @Test
    void knowsYouWhenYouLookAtYourself() {
        assertThat(service.relationship(ASKER, ASKER)).isEqualTo(RelationshipDto.SELF);
    }

    @Test
    void removingAFriendshipThatIsNotThereSucceedsQuietly() {
        service.remove(ASKER, ASKED);

        verify(friendshipRepository, never()).delete(any());
    }

    @Test
    void countsTheFriendsTwoPeopleHaveInCommon() {
        UUID shared = UUID.randomUUID();
        UUID mineOnly = UUID.randomUUID();
        UUID theirsOnly = UUID.randomUUID();
        when(friendshipRepository.findFriendIds(ASKER)).thenReturn(List.of(shared, mineOnly));
        when(friendshipRepository.findFriendIds(ASKED)).thenReturn(List.of(shared, theirsOnly));

        assertThat(service.mutualFriendCount(ASKER, ASKED)).isEqualTo(1);
    }

    @Test
    void aPendingFriendshipIsNotAFriendship() {
        when(friendshipRepository.findBetween(ASKER, ASKED)).thenReturn(Optional.of(pending(ASKER, ASKED)));

        assertThat(service.areFriends(ASKER, ASKED)).isFalse();
    }
}
