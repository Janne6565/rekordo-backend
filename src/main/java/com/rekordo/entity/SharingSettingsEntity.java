package com.rekordo.entity;

import com.rekordo.model.core.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * What one account has decided to share.
 *
 * A missing row means the defaults, so an account that has never opened the Friends tab
 * behaves exactly like one that opened it and changed nothing.
 */
@Entity
@Table(name = "sharing_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SharingSettingsEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_visibility", nullable = false)
    private Visibility collectionVisibility = Visibility.FRIENDS;

    @Enumerated(EnumType.STRING)
    @Column(name = "wishlist_visibility", nullable = false)
    private Visibility wishlistVisibility = Visibility.FRIENDS;

    @Column(name = "prices_public", nullable = false)
    private boolean pricesPublic;

    @Column(name = "ratings_shared", nullable = false)
    private boolean ratingsShared;

    @Column(nullable = false)
    private boolean findable = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The settings an account has before it has ever saved any. */
    public static SharingSettingsEntity defaultsFor(UUID userId) {
        SharingSettingsEntity settings = new SharingSettingsEntity();
        settings.setUserId(userId);
        Instant now = Instant.now();
        settings.setCreatedAt(now);
        settings.setUpdatedAt(now);
        return settings;
    }
}
