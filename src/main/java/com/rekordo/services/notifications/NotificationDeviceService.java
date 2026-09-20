package com.rekordo.services.notifications;

import com.rekordo.entity.NotificationDeviceEntity;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.core.NotificationDeviceDto;
import com.rekordo.repository.NotificationDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where a push could arrive, and whether it currently may (design 22a, second list).
 *
 * <p>The split is the point: <em>what</em> may reach you is the account's and lives in
 * {@link NotificationPreferenceService}; <em>which device</em> buzzes is the device's and
 * lives here. A phone in a drawer and a phone in a pocket disagree, and one mute per device
 * is the whole of that disagreement — the categories are never duplicated per phone.
 */
@Service
@RequiredArgsConstructor
public class NotificationDeviceService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeviceService.class);

    private final NotificationDeviceRepository repository;

    /**
     * Records where this device can be reached, or updates the row it already had.
     *
     * <p>Keyed on the client's own device id rather than on the token: a token is reissued
     * on reinstall and after some OS updates, so keying on it would grow one row per phone
     * per reinstall and buzz the same person twice.
     *
     * <p>A device that re-registers keeps its mute. Somebody who silenced a phone in June
     * did not un-silence it by opening the app.
     */
    @Transactional
    public NotificationDeviceEntity register(
            UserEntity user, String deviceId, String pushToken, String platform, String label) {
        NotificationDeviceEntity device = repository
                .findByUserIdAndDeviceId(user.getId(), deviceId)
                .orElseGet(() -> {
                    NotificationDeviceEntity fresh = new NotificationDeviceEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setUserId(user.getId());
                    fresh.setDeviceId(deviceId);
                    fresh.setCreatedAt(Instant.now());
                    return fresh;
                });
        device.setPushToken(pushToken);
        device.setPlatform(platform);
        device.setLabel(label == null || label.isBlank() ? null : label.trim());
        device.setLastSeenAt(Instant.now());
        repository.save(device);

        log.debug("Device {} registered for user {}", deviceId, user.getId());
        return device;
    }

    @Transactional(readOnly = true)
    public List<NotificationDeviceDto> list(UserEntity user, String currentDeviceId) {
        return repository.findAllByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .map(device -> new NotificationDeviceDto(
                        device.getId(),
                        device.getPlatform(),
                        device.getLabel(),
                        device.getMutedAt(),
                        device.getCreatedAt(),
                        device.getDeviceId().equals(currentDeviceId)))
                .toList();
    }

    /** Whether anything on this account could be buzzed at all — what 22a's push column asks. */
    @Transactional(readOnly = true)
    public boolean anyDevice(UserEntity user) {
        return !repository.findAllByUserIdOrderByCreatedAtAsc(user.getId()).isEmpty();
    }

    @Transactional
    public void setMuted(UserEntity user, UUID deviceId, boolean muted) {
        repository.findById(deviceId)
                // Scoped to the caller rather than trusted from the path: a device id is a
                // uuid somebody could otherwise guess their way around the account list with.
                .filter(device -> device.getUserId().equals(user.getId()))
                .ifPresent(device -> {
                    device.setMutedAt(muted ? Instant.now() : null);
                    repository.save(device);
                    log.debug("Device {} {} for user {}", deviceId, muted ? "muted" : "unmuted", user.getId());
                });
    }

    /** Every token that may currently be sent to for this account. */
    @Transactional(readOnly = true)
    public List<String> reachableTokens(UUID userId) {
        return repository.findAllByUserIdAndMutedAtIsNull(userId).stream()
                .map(NotificationDeviceEntity::getPushToken)
                .distinct()
                .toList();
    }

    /**
     * Forgets a token the push service says is dead.
     *
     * <p>A phone that was wiped or had the app deleted would otherwise keep its row forever,
     * and every later send would pay for it.
     */
    @Transactional
    public void forget(String pushToken) {
        List<NotificationDeviceEntity> dead = repository.findAllByPushToken(pushToken);
        if (!dead.isEmpty()) {
            repository.deleteAll(dead);
            log.info("Forgot {} device(s) whose token is no longer registered", dead.size());
        }
    }
}
