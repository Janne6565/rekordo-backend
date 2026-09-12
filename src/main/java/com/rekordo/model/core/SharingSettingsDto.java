package com.rekordo.model.core;

/**
 * The Sharing screen, 15f. Three lists, three separate answers, plus the handle the whole
 * thing hangs off.
 *
 * @param handle null when this account has never claimed one, which is what makes the app
 *               usable without ever opening Friends.
 */
public record SharingSettingsDto(
        String handle,
        boolean findable,
        Visibility collectionVisibility,
        Visibility wishlistVisibility,
        boolean pricesPublic,
        /** Whether the stars travel with the shelf. Off until it is turned on. */
        boolean ratingsShared,
        /** How many handle changes are left inside the current window. */
        int handleChangesRemaining) {}
