package com.rekordo.controller.v1.implementation;

import com.rekordo.controller.v1.schema.AuthApi;
import com.rekordo.model.action.CancelEmailChangeRequest;
import com.rekordo.model.action.ChangeEmailRequest;
import com.rekordo.model.action.ConfirmEmailRequest;
import com.rekordo.model.action.ForgotPasswordRequest;
import com.rekordo.model.action.RequestEmailConfirmationRequest;
import com.rekordo.model.action.LoginRequest;
import com.rekordo.model.action.RegisterRequest;
import com.rekordo.model.action.ResetPasswordRequest;
import com.rekordo.model.action.UpdateProfileRequest;
import com.rekordo.model.core.ChallengeDto;
import com.rekordo.model.core.EmailConfirmationDto;
import com.rekordo.model.core.SessionDto;
import com.rekordo.model.core.TokenMode;
import com.rekordo.model.core.UserDto;
import com.rekordo.security.CurrentUser;
import com.rekordo.services.auth.AuthService;
import com.rekordo.services.auth.EmailVerificationService;
import com.rekordo.services.auth.PasswordResetService;
import com.rekordo.services.auth.RefreshCookieFactory;
import com.rekordo.services.challenge.ChallengeAction;
import com.rekordo.services.challenge.TurnstileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final EmailVerificationService emailVerificationService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final TurnstileService turnstileService;
    private final CurrentUser currentUser;

    /*
     * The bot check runs before the service on all four gated endpoints, and never after.
     * Verifying afterwards would still have created the account -- and on the two that send
     * mail, would still have sent it.
     */

    @Override
    public ResponseEntity<SessionDto> register(RegisterRequest request, String tokenMode) {
        turnstileService.verify(request.turnstileToken(), ChallengeAction.REGISTER);
        return deliver(authService.register(request), TokenMode.fromHeader(tokenMode));
    }

    @Override
    public ResponseEntity<SessionDto> login(LoginRequest request, String tokenMode) {
        turnstileService.verify(request.turnstileToken(), ChallengeAction.LOGIN);
        return deliver(authService.login(request), TokenMode.fromHeader(tokenMode));
    }

    @Override
    public ResponseEntity<ChallengeDto> challenge() {
        return ResponseEntity.ok(new ChallengeDto(turnstileService.siteKey()));
    }

    @Override
    public ResponseEntity<SessionDto> refresh(String cookieToken, String bodyToken, String tokenMode) {
        TokenMode mode = TokenMode.fromHeader(tokenMode);
        // A native client has no cookie, so it sends the token it stored instead.
        String presented = mode == TokenMode.DIRECT ? bodyToken : cookieToken;
        // A cookie that arrived with no Max-Age is a session cookie, so this sign-in chose
        // not to be remembered; reissuing it as durable would quietly override that.
        boolean remember = mode == TokenMode.DIRECT || cookieToken != null;
        return deliver(authService.refresh(presented, remember), mode);
    }

    @Override
    public ResponseEntity<Void> forgotPassword(ForgotPasswordRequest request) {
        turnstileService.verify(request.turnstileToken(), ChallengeAction.FORGOT_PASSWORD);
        passwordResetService.request(request.email());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SessionDto> resetPassword(ResetPasswordRequest request, String tokenMode) {
        return deliver(
                authService.issueFor(passwordResetService.redeem(request.token(), request.password())),
                TokenMode.fromHeader(tokenMode));
    }

    @Override
    public ResponseEntity<UserDto> confirmEmail(ConfirmEmailRequest request) {
        return ResponseEntity.ok(AuthService.toDto(emailVerificationService.confirm(request.token())));
    }

    @Override
    public ResponseEntity<EmailConfirmationDto> emailConfirmation() {
        return ResponseEntity.ok(emailVerificationService.status(currentUser.require()));
    }

    @Override
    public ResponseEntity<EmailConfirmationDto> resendEmailConfirmation() {
        return ResponseEntity.ok(emailVerificationService.request(currentUser.require()));
    }

    @Override
    public ResponseEntity<Void> requestEmailConfirmation(RequestEmailConfirmationRequest request) {
        turnstileService.verify(request.turnstileToken(), ChallengeAction.REQUEST_EMAIL_CONFIRMATION);
        emailVerificationService.requestFor(request.email());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<EmailConfirmationDto> changeEmail(ChangeEmailRequest request) {
        return ResponseEntity.ok(
                emailVerificationService.requestChange(currentUser.require(), request.email(), request.password()));
    }

    @Override
    public ResponseEntity<EmailConfirmationDto> cancelEmailChange() {
        return ResponseEntity.ok(emailVerificationService.cancelPendingChange(currentUser.require()));
    }

    @Override
    public ResponseEntity<Void> cancelEmailChangeByToken(CancelEmailChangeRequest request) {
        emailVerificationService.cancelChange(request.token());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> logout() {
        // Only this device. The refresh token is stateless, so clearing the cookie is what
        // ends the session — and the phone's, minted from the same account, is none of this
        // request's business. Ending all of them is `logout-all`, which somebody chooses.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }

    @Override
    public ResponseEntity<Void> logoutEverywhere() {
        authService.signOutEverywhere(currentUser.require());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }

    @Override
    public ResponseEntity<UserDto> me() {
        return ResponseEntity.ok(AuthService.toDto(currentUser.require()));
    }

    @Override
    public ResponseEntity<UserDto> updateProfile(UpdateProfileRequest request) {
        return ResponseEntity.ok(authService.updateProfile(currentUser.require(), request.displayName()));
    }

    @Override
    public ResponseEntity<Void> deleteAccount() {
        authService.deleteAccount(currentUser.require());
        // The refresh cookie now points at an account that no longer exists, so it is
        // cleared here rather than left to expire on its own.
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }

    private ResponseEntity<SessionDto> deliver(AuthService.Session session, TokenMode mode) {
        if (mode == TokenMode.DIRECT) {
            SessionDto body = session.body();
            return ResponseEntity.ok(new SessionDto(body.accessToken(), session.refreshToken(), body.user()));
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.create(session.refreshToken(), session.remember()).toString())
                .body(session.body());
    }
}
