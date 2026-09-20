package com.rekordo.entity;

import com.rekordo.model.core.NotificationCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** One choice that differs from {@link NotificationCategory}'s default. */
@Entity
@Table(name = "notification_preferences")
@IdClass(NotificationPreferenceEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class NotificationPreferenceEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    /*
     * Stored as text, and the column is deliberately not a database enum: adding a category
     * would otherwise need an ALTER TYPE before any row could carry it, and a value the
     * server no longer knows has to be ignorable rather than fatal.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationCategory category;

    @Column(nullable = false)
    private boolean mail;

    @Column(nullable = false)
    private boolean push;

    /** JPA needs a class for the composite key; nothing else ever names it. */
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PUBLIC)
    public static class Key implements Serializable {
        private UUID userId;
        private NotificationCategory category;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && java.util.Objects.equals(userId, key.userId)
                    && category == key.category;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, category);
        }
    }
}
