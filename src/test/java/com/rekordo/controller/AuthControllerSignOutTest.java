package com.rekordo.controller;

import com.rekordo.configuration.JwtProperties;
import com.rekordo.controller.v1.implementation.AuthController;
import com.rekordo.entity.UserEntity;
import com.rekordo.security.CurrentUser;
import com.rekordo.services.challenge.TurnstileService;
import com.rekordo.services.auth.AuthService;
import com.rekordo.services.auth.EmailVerificationService;
import com.rekordo.services.auth.PasswordResetService;
import com.rekordo.services.auth.RefreshCookieFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signing out is about one device unless somebody says otherwise.
 *
 * Until this split, `logout` revoked every refresh token on the account — so signing out of
 * a browser silently signed the phone out too, and there was no way to ask for that on
 * purpose either.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerSignOutTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "0123456789012345678901234567890123456789",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            Duration.ofHours(12));

    @Mock private AuthService authService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private TurnstileService turnstileService;
    @Mock private CurrentUser currentUser;

    private AuthController controller() {
        return new AuthController(
                authService,
                passwordResetService,
                emailVerificationService,
                new RefreshCookieFactory(PROPERTIES, true),
                turnstileService,
                currentUser);
    }

    @Test
    void signingOutHereLeavesEveryOtherDeviceAlone() {
        var response = controller().logout();

        verify(authService, never()).signOutEverywhere(org.mockito.ArgumentMatchers.any());
        // The cookie is what holds the session, so clearing it is the whole of signing out.
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith("mc_refresh=") && cookie.contains("Max-Age=0"));
    }

    @Test
    void signingOutEverywhereRevokesTheAccount() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        when(currentUser.require()).thenReturn(user);

        var response = controller().logoutEverywhere();

        verify(authService).signOutEverywhere(user);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.contains("Max-Age=0"));
    }
}
