package com.rekordo.model.action;

import com.rekordo.model.core.Visibility;
import jakarta.validation.constraints.NotNull;

/**
 * The whole Sharing screen saved at once. Every field is required: a partial update would
 * make "leave this one alone" and "set this one to its default" indistinguishable, and on a
 * privacy screen that ambiguity is the wrong one to have.
 *
 * <p>{@code ratingsShared} is the one exception, and only for as long as older clients are
 * out there. It arrived after the phone app had shipped, so a build that predates it sends
 * the screen without the field; rejecting that would stop those users saving the settings
 * they do have. Null therefore means "leave it as it is" -- never "turn it off" -- and
 * since the stored default is off, an old client can only ever fail to turn it on, which is
 * the harmless direction. Make it {@code @NotNull} once the old builds are gone.
 */
public record UpdateSharingRequest(
        @NotNull Visibility collectionVisibility,
        @NotNull Visibility wishlistVisibility,
        @NotNull Boolean pricesPublic,
        Boolean ratingsShared,
        @NotNull Boolean findable) {}
