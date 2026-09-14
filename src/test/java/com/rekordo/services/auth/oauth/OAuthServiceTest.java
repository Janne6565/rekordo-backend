package com.rekordo.services.auth.oauth;

import com.rekordo.configuration.OAuthProperties;
import com.rekordo.entity.OAuthStateEntity;
import com.rekordo.model.core.OAuthClient;
import com.rekordo.model.exception.OAuthFailedException;
import com.rekordo.repository.OAuthStateRepository;
import com.rekordo.services.auth.OneTimeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    /** The secret the browser that started a flow is holding, in the tests that have one. */
    private static final String BINDING = "the-browser-that-started-it";

    @Mock private OAuthStateRepository stateRepository;

    private static OAuthProperties.Provider configured() {
        return new OAuthProperties.Provider(
                "Google", "client-id", "client-secret",
                "https://accounts.example/authorize", "https://accounts.example/token",
                "https://accounts.example/userinfo", "openid email", null, null, null);
    }

    private static OAuthProperties.Provider applePostsBack() {
        return new OAuthProperties.Provider(
                "Apple", "services-id", "key", "https://appleid.example/authorize",
                "https://appleid.example/token", null, "openid email name", "team", "kid", "form_post");
    }

    private static OAuthProperties.Provider unconfigured() {
        return new OAuthProperties.Provider(
                "Apple", null, null, "https://appleid.example/authorize",
                "https://appleid.example/token", null, "openid email", null, null, null);
    }

    private OAuthService service(Map<String, OAuthProperties.Provider> providers) {
        return service(providers, List.of());
    }

    private OAuthService service(Map<String, OAuthProperties.Provider> providers, List<String> callbackOrigins) {
        return new OAuthService(
                new OAuthProperties(
                        "https://music.example", "musiccollector://auth/callback", providers, callbackOrigins),
                stateRepository);
    }

    @Test
    void asksAppleToPostTheCallbackBack() {
        // Apple rejects the request outright if name or e-mail scope is asked for without
        // this, so its absence would break every Apple sign-in on the first attempt.
        String url = service(Map.of("apple", applePostsBack())).authorizeUrl("apple", OAuthClient.WEB, null).url();

        assertThat(url).contains("response_mode=form_post");
    }

    @Test
    void leavesResponseModeOffForProvidersThatRedirectNormally() {
        String url = service(Map.of("google", configured())).authorizeUrl("google", OAuthClient.WEB, null).url();

        assertThat(url).doesNotContain("response_mode");
    }

    @Test
    void listsOnlyProvidersThatCouldActuallyCompleteASignIn() {
        // The client renders a button per entry, so an unconfigured provider must be
        // absent rather than a button that fails when pressed.
        var available = service(Map.of("google", configured(), "apple", unconfigured())).available();

        assertThat(available).extracting("id").containsExactly("google");
    }

    @Test
    void refusesToStartAFlowForAnUnconfiguredProvider() {
        var service = service(Map.of("apple", unconfigured()));

        assertThatThrownBy(() -> service.authorizeUrl("apple", OAuthClient.WEB, null))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void refusesAProviderItHasNeverHeardOf() {
        var service = service(Map.of("google", configured()));

        assertThatThrownBy(() -> service.authorizeUrl("myspace", OAuthClient.WEB, null))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void buildsAnAuthorizeUrlCarryingAFreshState() {
        when(stateRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        String url = service(Map.of("google", configured())).authorizeUrl("google", OAuthClient.WEB, null).url();

        assertThat(url).startsWith("https://accounts.example/authorize");
        assertThat(url).contains("client_id=client-id");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("state=");
        assertThat(url).contains("redirect_uri=");
    }

    @Test
    void theAuthorizeUrlIsEncodedSoItIsAValidUri() {
        // Regression: a multi-scope value contains spaces, and an unencoded query made
        // URI.create throw on the very first sign-in with any real provider.
        when(stateRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        String url = service(Map.of("google", configured())).authorizeUrl("google", OAuthClient.WEB, null).url();

        assertThat(url).doesNotContain(" ");
        assertThat(url).contains("scope=openid%20email");
        assertThat(java.net.URI.create(url)).isNotNull();
    }

    @Test
    void theRedirectUriMatchesWhatTheProviderMustHaveRegistered() {
        assertThat(service(Map.of("google", configured())).redirectUri("google", null))
                .isEqualTo("https://music.example/api/v1/auth/oauth/google/callback");
    }

    @Test
    void aHostItDoesNotKnowGetsThePublicBaseUrl() {
        var service = service(Map.of("google", configured()), List.of("https://old.example"));

        assertThat(service.redirectUri("google", "   "))
                .isEqualTo("https://music.example/api/v1/auth/oauth/google/callback");
        assertThat(service.redirectUri("google", "elsewhere.example"))
                .isEqualTo("https://music.example/api/v1/auth/oauth/google/callback");
    }

    @Test
    void aSignInStartedOnTheOldHostComesBackToTheOldHost() {
        // A phone build from before the move still starts sign-in there, and its state cookie
        // lives there. Sent to the new host, the callback would arrive without it and refuse.
        var service = service(Map.of("google", configured()), List.of("https://old.example/"));

        assertThat(service.redirectUri("google", "old.example"))
                .isEqualTo("https://old.example/api/v1/auth/oauth/google/callback");
    }

    @Test
    void theHostMatchesWhateverItsCaseAndPort() {
        // The configured origin is used as written, never the request's own scheme or port:
        // TLS ends at the ingress, so this server sees plain http on an internal port.
        var service = service(Map.of("google", configured()), List.of("https://old.example"));

        assertThat(service.redirectUri("google", "OLD.Example:8080"))
                .isEqualTo("https://old.example/api/v1/auth/oauth/google/callback");
        assertThat(service.redirectUri("google", "Music.Example:443"))
                .isEqualTo("https://music.example/api/v1/auth/oauth/google/callback");
    }

    @Test
    void theAuthorizeUrlSendsTheProviderBackToTheHostTheFlowStartedOn() {
        when(stateRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        String url = service(Map.of("google", configured()), List.of("https://old.example"))
                .authorizeUrl("google", OAuthClient.MOBILE, "old.example")
                .url();

        assertThat(url).contains("redirect_uri=https://old.example/api/v1/auth/oauth/google/callback");
    }

    @Test
    void aSpoofedHostNeverReachesTheRedirectUri() {
        // The host header is whatever the caller wrote. Followed blindly, it would send the
        // provider's code to a server of the caller's choosing.
        when(stateRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        var service = service(Map.of("google", configured()), List.of("https://old.example"));

        String url = service.authorizeUrl("google", OAuthClient.WEB, "attacker.example").url();

        assertThat(url).doesNotContain("attacker.example");
        assertThat(url).contains("redirect_uri=https://music.example/api/v1/auth/oauth/google/callback");
        assertThat(service.redirectUri("google", "old.example.attacker.example"))
                .doesNotContain("attacker.example");
    }

    @Test
    void aCallbackFromABrowserThatDidNotStartTheFlowIsRefused() {
        // Login CSRF: an attacker begins a sign-in, holds the callback URL and gets somebody
        // else to load it. Without this the victim's browser is signed into the attacker's
        // account, and every record it adds from then on goes there.
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), null)));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", "someone-else"))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void aCallbackPresentingNoBindingAtAllIsRefused() {
        // A browser that never went through the authorize step has no cookie to send. That
        // is the ordinary shape of the attack, not an edge case.
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), null)));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", null))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void aStateWrittenBeforeBindingsExistedIsRefused() {
        // Rows in flight across the deploy. Refusing costs one retry inside the state's
        // ten-minute life; accepting would leave the hole open for exactly as long as an
        // attacker was willing to pre-warm a state before it.
        OAuthStateEntity unbound = state("google", Instant.now().plusSeconds(60), null);
        unbound.setBindingHash(null);
        when(stateRepository.findById("s")).thenReturn(Optional.of(unbound));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void aRefusedBindingBurnsTheStateAnyway() {
        // It is now known to somebody it was not issued to. Leaving it live would let them
        // keep trying it against one browser after another.
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), null)));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", "someone-else"))
                .isInstanceOf(OAuthFailedException.class);

        verify(stateRepository).save(argThat(saved -> saved.getUsedAt() != null));
    }

    @Test
    void theAuthorizeStepHandsBackASecretForTheBrowserToKeep() {
        when(stateRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        OAuthService.Authorization authorization =
                service(Map.of("google", configured())).authorizeUrl("google", OAuthClient.WEB, null);

        // Never the state itself, and never in the URL: the point is that it travels by a
        // route the provider's redirect does not.
        assertThat(authorization.binding()).isNotBlank();
        assertThat(authorization.url()).doesNotContain(authorization.binding());
        verify(stateRepository)
                .save(argThat(saved -> OneTimeToken.hash(authorization.binding()).equals(saved.getBindingHash())));
    }

    private OAuthStateEntity state(String provider, Instant expiresAt, Instant usedAt) {
        return state(provider, expiresAt, usedAt, OAuthClient.WEB);
    }

    private OAuthStateEntity state(String provider, Instant expiresAt, Instant usedAt, OAuthClient client) {
        OAuthStateEntity entity = new OAuthStateEntity();
        entity.setState("s");
        entity.setProvider(provider);
        entity.setClient(client);
        entity.setBindingHash(OneTimeToken.hash(BINDING));
        entity.setExpiresAt(expiresAt);
        entity.setUsedAt(usedAt);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    @Test
    void aStateIsAcceptedExactlyOnce() {
        // A replayed callback must not be able to mint a second session.
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), Instant.now())));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void anExpiredStateIsRefused() {
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().minusSeconds(1), null)));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void aStateIssuedForAnotherProviderIsRefused() {
        // Otherwise a state from a provider the attacker controls could complete a flow
        // against a different one.
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("apple", Instant.now().plusSeconds(60), null)));

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void aMissingStateIsRefused() {
        when(stateRepository.findById("")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(Map.of("google", configured())).consumeState("google", null, BINDING))
                .isInstanceOf(OAuthFailedException.class);
    }

    @Test
    void remembersWhichClientStartedTheFlow() {
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), null, OAuthClient.MOBILE)));

        // The callback arrives from the provider and carries nothing about who asked, so
        // the only place this can come from is the row written at the authorize step.
        assertThat(service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isEqualTo(OAuthClient.MOBILE);
    }

    @Test
    void treatsAStateWrittenBeforeMobileExistedAsWeb() {
        when(stateRepository.findById("s"))
                .thenReturn(Optional.of(state("google", Instant.now().plusSeconds(60), null, null)));

        assertThat(service(Map.of("google", configured())).consumeState("google", "s", BINDING))
                .isEqualTo(OAuthClient.WEB);
    }

    @Test
    void readsTheClientOfAnAlreadyConsumedStateWithoutThrowing() {
        // The failure path needs to know where to send somebody back to, and by then the
        // state has usually been consumed already.
        when(stateRepository.findById("s")).thenReturn(Optional.of(
                state("google", Instant.now().minusSeconds(1), Instant.now(), OAuthClient.MOBILE)));

        assertThat(service(Map.of("google", configured())).clientFor("s")).isEqualTo(OAuthClient.MOBILE);
    }

    @Test
    void fallsBackToTheWebAppForAnUnknownState() {
        // A deep link into an app that may not be the one in front of the person is worse
        // than a web page that can at least say what went wrong.
        when(stateRepository.findById("nope")).thenReturn(Optional.empty());

        assertThat(service(Map.of("google", configured())).clientFor("nope")).isEqualTo(OAuthClient.WEB);
    }
}
