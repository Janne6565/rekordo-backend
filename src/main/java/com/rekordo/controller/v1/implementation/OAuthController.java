package com.rekordo.controller.v1.implementation;

import com.rekordo.configuration.OAuthProperties;
import com.rekordo.controller.v1.schema.OAuthApi;
import com.rekordo.entity.UserEntity;
import com.rekordo.model.action.OAuthExchangeRequest;
import com.rekordo.model.core.AuthProviderDto;
import com.rekordo.model.core.OAuthClient;
import com.rekordo.model.core.SessionDto;
import com.rekordo.services.auth.AuthService;
import com.rekordo.services.auth.RefreshCookieFactory;
import com.rekordo.services.auth.oauth.OAuthHandoffService;
import com.rekordo.services.auth.oauth.OAuthService;
import com.rekordo.services.auth.oauth.OAuthStateCookieFactory;
import com.rekordo.services.auth.oauth.OAuthUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class OAuthController implements OAuthApi {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;
    private final OAuthUserResolver userResolver;
    private final AuthService authService;
    private final OAuthHandoffService handoffService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final OAuthStateCookieFactory stateCookieFactory;
    private final OAuthProperties properties;

    @Override
    public ResponseEntity<List<AuthProviderDto>> providers() {
        return ResponseEntity.ok(oauthService.available());
    }

    @Override
    public ResponseEntity<Void> authorize(String provider, String client, HttpServletRequest request) {
        OAuthService.Authorization authorization =
                oauthService.authorizeUrl(provider, OAuthClient.fromParam(client), request.getServerName());
        // The redirect carries the cookie that says this browser is the one signing in. It
        // has to be set here rather than at the callback, because by then the only thing
        // proving anything is whether it came back.
        return ResponseEntity.status(302)
                .header(
                        HttpHeaders.SET_COOKIE,
                        stateCookieFactory
                                .create(authorization.binding(), OAuthService.STATE_LIFETIME)
                                .toString())
                .location(URI.create(authorization.url()))
                .build();
    }

    @Override
    public ResponseEntity<Void> callback(
            String provider, String code, String state, String error, String binding, HttpServletRequest request) {
        return complete(provider, code, state, error, null, binding, request.getServerName());
    }

    @Override
    public ResponseEntity<Void> callbackPosted(
            String provider,
            String code,
            String state,
            String error,
            String user,
            String binding,
            HttpServletRequest request) {
        return complete(provider, code, state, error, user, binding, request.getServerName());
    }

    @Override
    public ResponseEntity<SessionDto> exchange(OAuthExchangeRequest request) {
        AuthService.Session session = authService.issueFor(handoffService.redeem(request.code()));
        // Always DIRECT: a handoff code only ever exists because a native client started
        // the flow, and a cookie set on this response would go to nobody.
        return ResponseEntity.ok(
                new SessionDto(session.body().accessToken(), session.refreshToken(), session.body().user()));
    }

    private ResponseEntity<Void> complete(
            String provider,
            String code,
            String state,
            String error,
            String appleUserJson,
            String binding,
            String host) {
        if (error != null || code == null) {
            // Read rather than consumed: there is nothing to complete, and burning the state
            // would only stop the person retrying from the screen they are about to see.
            return failed(oauthService.clientFor(state));
        }
        try {
            OAuthClient client = oauthService.consumeState(provider, state, binding);
            // The callback's own host, because it is the one the authorize step named in the
            // redirect URI, and the exchange has to repeat that URI exactly.
            UserEntity user = userResolver.resolve(
                    provider, oauthService.named(oauthService.exchange(provider, code, host), appleUserJson));

            if (client == OAuthClient.MOBILE) {
                // The app cannot read the browser's cookie jar, so it gets a one-time code
                // instead and trades it for the session itself. A refresh token in this URL
                // would be a durable credential passing through the OS.
                return ResponseEntity.status(302)
                        .header(HttpHeaders.SET_COOKIE, stateCookieFactory.clear().toString())
                        .location(URI.create(UriComponentsBuilder.fromUriString(oauthService.mobileRedirectUri())
                                .queryParam("code", handoffService.issue(user))
                                .encode()
                                .toUriString()))
                        .build();
            }

            // The token goes in a cookie, never in the URL — a redirect target ends up in
            // browser history, server logs and the Referer header.
            AuthService.Session session = authService.issueFor(user);
            return ResponseEntity.status(302)
                    .header(
                            HttpHeaders.SET_COOKIE,
                            refreshCookieFactory.create(session.refreshToken(), true).toString())
                    .header(HttpHeaders.SET_COOKIE, stateCookieFactory.clear().toString())
                    .location(URI.create(appUrl("/?signedIn=1")))
                    .build();
        } catch (RuntimeException e) {
            log.warn("External sign-in with {} failed", provider, e);
            // The state is consumed by now, but the row still records who started the flow,
            // so even a failure lands back in the client the person is actually looking at.
            return failed(oauthService.clientFor(state));
        }
    }

    /** Back to whichever client started this, with enough to say that it did not work. */
    private ResponseEntity<Void> failed(OAuthClient client) {
        String target = client == OAuthClient.MOBILE
                ? UriComponentsBuilder.fromUriString(oauthService.mobileRedirectUri())
                        .queryParam("error", "oauth")
                        .encode()
                        .toUriString()
                : appUrl("/signin?oauthError=true");
        return ResponseEntity.status(302)
                .header(HttpHeaders.SET_COOKIE, stateCookieFactory.clear().toString())
                .location(URI.create(target))
                .build();
    }

    private String appUrl(String path) {
        String base = properties.publicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }
}
