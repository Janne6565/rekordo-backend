package com.rekordo.services.social;

import com.rekordo.entity.SharingSettingsEntity;
import com.rekordo.model.action.UpdateSharingRequest;
import com.rekordo.model.core.SharingSettingsDto;
import com.rekordo.repository.SharingSettingsRepository;
import com.rekordo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each account has decided to share.
 *
 * <p>A missing row is not a missing answer: it is the defaults. Every read goes through
 * {@link #settingsFor} so that an account which has never opened the Sharing screen behaves
 * identically to one that opened it and saved nothing.
 */
@Service
@RequiredArgsConstructor
public class SharingService {

    private static final Logger log = LoggerFactory.getLogger(SharingService.class);

    private final SharingSettingsRepository repository;
    private final UserRepository userRepository;
    private final HandleService handleService;

    @Transactional(readOnly = true)
    public SharingSettingsEntity settingsFor(UUID userId) {
        return repository.findById(userId).orElseGet(() -> SharingSettingsEntity.defaultsFor(userId));
    }

    /**
     * Settings for several accounts at once, defaults filled in for the ones with no row.
     * Used by the list screens, which would otherwise ask per person.
     */
    @Transactional(readOnly = true)
    public Map<UUID, SharingSettingsEntity> settingsFor(Collection<UUID> userIds) {
        Map<UUID, SharingSettingsEntity> byUser = new HashMap<>();
        for (SharingSettingsEntity settings : repository.findAllByUserIdIn(userIds)) {
            byUser.put(settings.getUserId(), settings);
        }
        for (UUID userId : userIds) {
            byUser.computeIfAbsent(userId, SharingSettingsEntity::defaultsFor);
        }
        return byUser;
    }

    @Transactional(readOnly = true)
    public SharingSettingsDto read(UUID userId) {
        return toDto(userId, settingsFor(userId));
    }

    @Transactional
    public SharingSettingsDto update(UUID userId, UpdateSharingRequest request) {
        SharingSettingsEntity settings = settingsFor(userId);
        settings.setCollectionVisibility(request.collectionVisibility());
        settings.setWishlistVisibility(request.wishlistVisibility());
        settings.setPricesPublic(request.pricesPublic());
        // Absent means untouched: see UpdateSharingRequest on why this one field may be null.
        if (request.ratingsShared() != null) {
            settings.setRatingsShared(request.ratingsShared());
        }
        settings.setFindable(request.findable());
        settings.setUpdatedAt(Instant.now());
        if (settings.getCreatedAt() == null) {
            settings.setCreatedAt(settings.getUpdatedAt());
        }
        repository.save(settings);

        log.debug(
                "User {} now shares collection={} wishlist={} prices={} ratings={} findable={}",
                userId,
                settings.getCollectionVisibility(),
                settings.getWishlistVisibility(),
                settings.isPricesPublic(),
                settings.isRatingsShared(),
                settings.isFindable());
        return toDto(userId, settings);
    }

    private SharingSettingsDto toDto(UUID userId, SharingSettingsEntity settings) {
        String handle = userRepository.findById(userId).map(user -> user.getHandle()).orElse(null);
        return new SharingSettingsDto(
                handle,
                settings.isFindable(),
                settings.getCollectionVisibility(),
                settings.getWishlistVisibility(),
                settings.isPricesPublic(),
                settings.isRatingsShared(),
                handleService.changesRemaining(userId));
    }
}
