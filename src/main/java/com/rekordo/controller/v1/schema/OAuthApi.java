package com.rekordo.controller.v1.schema;

import com.rekordo.model.action.OAuthExchangeRequest;
import com.rekordo.model.core.AuthProviderDto;
import com.rekordo.model.core.SessionDto;
import com.rekordo.services.auth.oauth.OAuthStateCookieFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Signing in with an external provider.
 *
 * <p>The provider only authenticates the first step; the app then issues its own tokens,
 * exactly as a password login does, so nothing downstream knows how someone signed in.
 */
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public interface OAuthApi {

    @GetMapping("/providers")
    @Operation(
            summary = "Sign-in providers this server can actually use",
            description = "Only providers with credentials configured. Clients render a button per "
                    + "entry, so an unconfigured provider is absent rather than broken.")
    @ApiResponse(responseCode = "200", description = "The available providers, possibly none")
    ResponseEntity<List<AuthProviderDto>> providers();

    @GetMapping("/oauth/{provider}/authorize")
    @Operation(
            summary = "Begin an external sign-in",
            description = "Redirects to the provider. Navigate to this, never fetch it -- it "
                    + "sets the cookie that ties the flow to this browser, and a fetched "
                    + "redirect keeps it. `client=mobile` finishes the flow by reopening the "
                    + "native app with a one-time code instead of setting the refresh cookie.")
    @ApiResponse(responseCode = "302", description = "Redirect to the provider")
    ResponseEntity<Void> authorize(
            @PathVariable String provider,
            @RequestParam(required = false) String client,
            // Not an API parameter: springdoc leaves the servlet request out of the schema.
            // It supplies the host the flow started on, which the redirect URI has to follow.
            HttpServletRequest request);

    @GetMapping("/oauth/{provider}/callback")
    @Operation(
            summary = "Where the provider sends the person back",
            description = "For a browser: sets the refresh cookie and redirects into the web "
                    + "app. For a native client: redirects to the app's URL scheme with a "
                    + "one-time handoff code. No token ever appears in a URL either way.")
    @ApiResponse(responseCode = "302", description = "Redirect into the app")
    ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = OAuthStateCookieFactory.COOKIE_NAME, required = false) String binding,
            HttpServletRequest request);

    @PostMapping("/oauth/{provider}/callback")
    @Operation(
            summary = "The same callback, for a provider that posts it back",
            description = "Apple answers with a cross-site form POST rather than a redirect "
                    + "whenever name or e-mail scope was requested, and sends the name in a "
                    + "`user` field that appears on the first authorization only.")
    @ApiResponse(responseCode = "302", description = "Redirect into the app")
    ResponseEntity<Void> callbackPosted(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String user,
            @CookieValue(name = OAuthStateCookieFactory.COOKIE_NAME, required = false) String binding,
            HttpServletRequest request);

    @PostMapping("/oauth/exchange")
    @Operation(
            summary = "Trade a handoff code for a session",
            description = "The native half of the flow. The deep link carries only a one-time "
                    + "code, which the app redeems here over its own connection; the refresh "
                    + "token is returned in the body, as `X-Token-Mode: direct` would.")
    @ApiResponse(responseCode = "200", description = "Signed in")
    @ApiResponse(responseCode = "400", description = "The code is unknown, used or expired")
    ResponseEntity<SessionDto> exchange(@Valid @RequestBody OAuthExchangeRequest request);
}
