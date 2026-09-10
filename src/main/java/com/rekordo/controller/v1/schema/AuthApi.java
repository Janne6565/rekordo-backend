package com.rekordo.controller.v1.schema;

import com.rekordo.model.action.CancelEmailChangeRequest;
import com.rekordo.model.action.ChangeEmailRequest;
import com.rekordo.model.action.ConfirmEmailRequest;
import com.rekordo.model.action.ForgotPasswordRequest;
import com.rekordo.model.action.RequestEmailConfirmationRequest;
import com.rekordo.model.action.LoginRequest;
import com.rekordo.model.action.RegisterRequest;
import com.rekordo.model.action.ResetPasswordRequest;
import com.rekordo.model.action.UpdateProfileRequest;
import com.rekordo.model.core.SessionDto;
import com.rekordo.model.core.ChallengeDto;
import com.rekordo.model.core.EmailConfirmationDto;
import com.rekordo.model.core.UserDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;

/**
 * Accounts, which are entirely optional.
 *
 * The app works with no account at all — everything lives on the device. Signing in adds
 * cross-device sync and nothing else, so none of these endpoints gate any feature.
 */
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public interface AuthApi {

    @PostMapping("/register")
    @Operation(summary = "Create an account", description = "Also sets the refresh cookie.")
    @ApiResponse(responseCode = "200", description = "Account created and signed in")
    @ApiResponse(responseCode = "403", description = "The bot check was not passed")
    @ApiResponse(responseCode = "409", description = "That e-mail is already registered")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<SessionDto> register(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader(name = "X-Token-Mode", required = false) String tokenMode);

    @PostMapping("/login")
    @Operation(summary = "Sign in", description = "Also sets the refresh cookie.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "401", description = "Wrong e-mail or password")
    @ApiResponse(responseCode = "403", description = "The bot check was not passed")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<SessionDto> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(name = "X-Token-Mode", required = false) String tokenMode);

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token")
    @ApiResponse(responseCode = "200", description = "A fresh access token")
    @ApiResponse(responseCode = "401", description = "No valid refresh cookie")
    ResponseEntity<SessionDto> refresh(
            @CookieValue(name = "mc_refresh", required = false) String cookieToken,
            @RequestHeader(name = "X-Refresh-Token", required = false) String bodyToken,
            @RequestHeader(name = "X-Token-Mode", required = false) String tokenMode);

    @PostMapping("/logout")
    @Operation(
            summary = "Sign out on this device",
            description = "Clears the refresh cookie. Sessions on other devices are left alone — "
                    + "signing out of a browser is not a statement about a phone.")
    @ApiResponse(responseCode = "204", description = "Signed out")
    ResponseEntity<Void> logout();

    @PostMapping("/logout-all")
    @Operation(
            summary = "Sign out everywhere",
            description = "Invalidates every outstanding refresh token for this account, on every "
                    + "device, and clears this one's cookie. The deliberate version of what logout "
                    + "used to do to everybody.")
    @ApiResponse(responseCode = "204", description = "Signed out everywhere")
    ResponseEntity<Void> logoutEverywhere();

    @PostMapping("/forgot-password")
    @Operation(
            summary = "Send a password reset link",
            description = "Always answers 204, whether or not the address has an account — "
                    + "a different answer would turn this into a way to find out who is registered.")
    @ApiResponse(responseCode = "204", description = "Handled")
    @ApiResponse(responseCode = "403", description = "The bot check was not passed")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request);

    @PostMapping("/reset-password")
    @Operation(
            summary = "Redeem a reset link and sign in",
            description = "Revokes every other session, since a reset may be locking somebody out.")
    @ApiResponse(responseCode = "200", description = "Password changed and signed in")
    @ApiResponse(responseCode = "400", description = "The link is expired, used, or not valid")
    ResponseEntity<SessionDto> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            @RequestHeader(name = "X-Token-Mode", required = false) String tokenMode);

    @PostMapping("/confirm-email")
    @Operation(
            summary = "Redeem a confirmation link",
            description = "Open, because the link is followed in whichever browser opened the mail, "
                    + "which is not necessarily one that is signed in. The token is the proof. "
                    + "A token issued for an address change moves the account instead.")
    @ApiResponse(responseCode = "200", description = "The account, now confirmed")
    @ApiResponse(responseCode = "400", description = "The link is expired, used, or not valid")
    @ApiResponse(responseCode = "409", description = "The new address was claimed while the link waited")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<UserDto> confirmEmail(@Valid @RequestBody ConfirmEmailRequest request);

    @GetMapping("/confirm-email")
    @Operation(
            summary = "Whether the address is confirmed, and what link is outstanding",
            description = "What the account row draws. It survives a reload, which a client that only "
                    + "remembered its own last button press would not.")
    @ApiResponse(responseCode = "200", description = "The state of the address on this account")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<EmailConfirmationDto> emailConfirmation();

    @PostMapping("/confirm-email/resend")
    @Operation(
            summary = "Send a fresh confirmation link",
            description = "Silent whether or not there was anything to send -- already confirmed is the "
                    + "state the caller wanted, not an error. Inside the first minute nothing is sent "
                    + "and the answer carries the seconds left instead; issuing a link always retires "
                    + "the previous one, so two are never live at once.")
    @ApiResponse(responseCode = "200", description = "The state of the address, including any countdown")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<EmailConfirmationDto> resendEmailConfirmation();

    @PostMapping("/confirm-email/request")
    @Operation(
            summary = "Send a confirmation link to an address, with no session",
            description = "For the browser that landed on a dead link and is not signed in. Always "
                    + "answers 204 -- an address with no account and one already confirmed answer the "
                    + "same, or this becomes a way to find out who is registered.")
    @ApiResponse(responseCode = "204", description = "Handled")
    @ApiResponse(responseCode = "403", description = "The bot check was not passed")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<Void> requestEmailConfirmation(@Valid @RequestBody RequestEmailConfirmationRequest request);

    @PostMapping("/email-change")
    @Operation(
            summary = "Start moving the account to a different address",
            description = "Nothing about the account changes here. The old address goes on signing you in "
                    + "and receiving resets until the new one answers, so a typo cannot lock anybody out. "
                    + "The password is asked for so that a stray session cannot walk off with the account; "
                    + "an account made through a provider has none and is not asked.")
    @ApiResponse(responseCode = "200", description = "The change is waiting on the new address")
    @ApiResponse(responseCode = "401", description = "Not signed in, or the password is wrong")
    @ApiResponse(responseCode = "409", description = "That address already has an account")
    ResponseEntity<EmailConfirmationDto> changeEmail(@Valid @RequestBody ChangeEmailRequest request);

    @DeleteMapping("/email-change")
    @Operation(
            summary = "Call off a change that has not landed yet",
            description = "The Cancel on the account row. Undoing one that has already landed is "
                    + "/email-change/cancel, which works from the old mailbox without a session.")
    @ApiResponse(responseCode = "200", description = "The state of the address, with nothing pending")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<EmailConfirmationDto> cancelEmailChange();

    @PostMapping("/email-change/cancel")
    @Operation(
            summary = "Undo a change from the link in the notice",
            description = "Open, and deliberately outlives the change by a day: it is the only defence "
                    + "if somebody else is at the keyboard, and it has to work from a mailbox that can "
                    + "no longer sign in. Undoing signs every device out.")
    @ApiResponse(responseCode = "204", description = "The account is back on the old address")
    @ApiResponse(responseCode = "400", description = "The link is expired, used, or not valid")
    @ApiResponse(responseCode = "429", description = "Too many attempts")
    ResponseEntity<Void> cancelEmailChangeByToken(@Valid @RequestBody CancelEmailChangeRequest request);

    @GetMapping("/challenge")
    @Operation(
            summary = "What a client needs to draw the bot check",
            description = "Open, because it is asked before anybody has signed in, and public: the "
                    + "site key it returns is rendered into the widget's markup anyway. Asked for "
                    + "rather than built into the client because one frontend image serves both "
                    + "staging and production, and a shipped phone binary cannot be reconfigured at "
                    + "all. A null site key means the check is off and no token will be required.")
    @ApiResponse(responseCode = "200", description = "The site key, or null when the check is off")
    ResponseEntity<ChallengeDto> challenge();

    @GetMapping("/me")
    @Operation(summary = "The signed-in account")
    @ApiResponse(responseCode = "200", description = "The account")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<UserDto> me();

    @PatchMapping("/me")
    @Operation(
            summary = "Change the account's display name",
            description = "The name the app greets you by and the one friends see. "
                    + "A blank name clears it; the e-mail then stands in for it as before.")
    @ApiResponse(responseCode = "200", description = "The account, as it now reads")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<UserDto> updateProfile(@Valid @RequestBody UpdateProfileRequest request);

    @DeleteMapping("/me")
    @Operation(
            summary = "Delete the account and everything synced to it",
            description = "Removes the server-side copy of the collection and every uploaded photo. "
                    + "The client's local collection is untouched -- it belongs to the device, not "
                    + "the account, and the app goes on working without one.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "401", description = "Not signed in")
    ResponseEntity<Void> deleteAccount();
}
