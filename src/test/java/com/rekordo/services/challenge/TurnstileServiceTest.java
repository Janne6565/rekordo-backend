package com.rekordo.services.challenge;

import com.rekordo.configuration.TurnstileProperties;
import com.rekordo.model.exception.ChallengeFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The gate in front of sign-up, sign-in and the two endpoints that send mail.
 *
 * <p>What is worth pinning here is not the happy path but the four ways a token can look
 * fine and still be worthless: a rejected verdict, a verdict for a different endpoint, one
 * solved on a different site, and Cloudflare not answering at all. Each of them has to end
 * the request, because every one of them is reachable by somebody trying.
 */
class TurnstileServiceTest {

    private static final String VERIFY_URL = "https://challenges.example.test/siteverify";

    private MockRestServiceServer server;

    private TurnstileService service(TurnstileProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new TurnstileService(builder.build(), properties);
    }

    /** Keys set and refusing, which is the end state. */
    private static TurnstileProperties configured() {
        return properties("0x-site-key", "0x-secret-key", Set.of("rekordo.example.test"), true);
    }

    /** Keys set and only watching, which is how the check is switched on for shipped apps. */
    private static TurnstileProperties observing() {
        return properties("0x-site-key", "0x-secret-key", Set.of("rekordo.example.test"), false);
    }

    private static TurnstileProperties properties(
            String siteKey, String secretKey, Set<String> hostnames, boolean enforce) {
        return new TurnstileProperties(
                siteKey, secretKey, VERIFY_URL, hostnames, enforce, Duration.ofSeconds(5));
    }

    private static MultiValueMap<String, String> form(String secret, String token) {
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("secret", secret);
        expected.add("response", token);
        return expected;
    }

    private void respond(String json) {
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void acceptsAVerdictForTheRightActionAndHost() {
        TurnstileService service = service(configured());
        respond("{\"success\":true,\"action\":\"login\",\"hostname\":\"rekordo.example.test\"}");

        assertThatCode(() -> service.verify("a-token", ChallengeAction.LOGIN)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void sendsTheSecretAndTheTokenAsAForm() {
        TurnstileService service = service(configured());
        server.expect(requestTo(VERIFY_URL))
                .andExpect(content().formData(form("0x-secret-key", "a-token")))
                .andRespond(withSuccess(
                        "{\"success\":true,\"action\":\"register\",\"hostname\":\"rekordo.example.test\"}",
                        MediaType.APPLICATION_JSON));

        service.verify("a-token", ChallengeAction.REGISTER);
        server.verify();
    }

    @Test
    void refusesAFailedVerdict() {
        TurnstileService service = service(configured());
        respond("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");

        assertThatThrownBy(() -> service.verify("a-spent-token", ChallengeAction.LOGIN))
                .isInstanceOf(ChallengeFailedException.class)
                // The reason never reaches the caller: "timeout-or-duplicate" would tell
                // somebody probing that their replay was noticed.
                .hasMessageNotContaining("duplicate");
    }

    /** A challenge solved on the sign-in form must not be spendable on the mail endpoints. */
    @Test
    void refusesATokenSolvedForADifferentAction() {
        TurnstileService service = service(configured());
        respond("{\"success\":true,\"action\":\"login\",\"hostname\":\"rekordo.example.test\"}");

        assertThatThrownBy(() -> service.verify("a-token", ChallengeAction.FORGOT_PASSWORD))
                .isInstanceOf(ChallengeFailedException.class);
    }

    @Test
    void refusesATokenSolvedOnAnotherSite() {
        TurnstileService service = service(configured());
        respond("{\"success\":true,\"action\":\"login\",\"hostname\":\"somebody-elses.example.test\"}");

        assertThatThrownBy(() -> service.verify("a-token", ChallengeAction.LOGIN))
                .isInstanceOf(ChallengeFailedException.class);
    }

    /**
     * Fail closed. Waving the request through when siteverify is unreachable would hand an
     * attacker the easiest bypass there is: make the call time out.
     */
    @Test
    void refusesWhenSiteverifyCannotBeReached() {
        TurnstileService service = service(configured());
        server.expect(requestTo(VERIFY_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> service.verify("a-token", ChallengeAction.LOGIN))
                .isInstanceOf(ChallengeFailedException.class);
    }

    @Test
    void refusesAMissingTokenWithoutCallingCloudflare() {
        TurnstileService service = service(configured());

        assertThatThrownBy(() -> service.verify(null, ChallengeAction.REGISTER))
                .isInstanceOf(ChallengeFailedException.class);
        // No expectation was registered, so a call would have failed the mock server.
        server.verify();
    }

    /** Over Cloudflare's 2048-character cap, so it is not a token and costs no round trip. */
    @Test
    void refusesAnOversizedTokenWithoutCallingCloudflare() {
        TurnstileService service = service(configured());

        assertThatThrownBy(() -> service.verify("x".repeat(2049), ChallengeAction.REGISTER))
                .isInstanceOf(ChallengeFailedException.class);
        server.verify();
    }

    /**
     * The local default. A laptop is not on the widget's domain list, so a required challenge
     * with no keys configured would be a form nobody could submit.
     */
    @Test
    void wavesEverythingThroughWhenNotConfigured() {
        TurnstileService service = service(properties("", "", Set.of(), true));

        assertThatCode(() -> service.verify(null, ChallengeAction.LOGIN)).doesNotThrowAnyException();
        assertThat(service.siteKey()).isNull();
        server.verify();
    }

    /** Half-configured is a deployment mistake, not a mode, and it fails on the way in. */
    @Test
    void refusesToStartWithOnlyOneOfTheTwoKeys() {
        assertThatThrownBy(() -> service(properties("0x-site-key", "", Set.of(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-key");
    }

    @Test
    void servesTheSiteKeyWhenConfigured() {
        assertThat(service(configured()).siteKey()).isEqualTo("0x-site-key");
    }

    /*
     * The middle state, and the only reason a check can be switched on for an app that is
     * already installed. Every one of these would be a refusal when enforcing; here the
     * verdict is reached, logged and ignored.
     */

    @Test
    void observingLetsATokenlessRequestThrough() {
        TurnstileService service = service(observing());

        assertThatCode(() -> service.verify(null, ChallengeAction.LOGIN)).doesNotThrowAnyException();
        // Nothing to verify, so Cloudflare is not called at all.
        server.verify();
    }

    @Test
    void observingLetsARejectedTokenThrough() {
        TurnstileService service = service(observing());
        respond("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");

        assertThatCode(() -> service.verify("a-spent-token", ChallengeAction.LOGIN))
                .doesNotThrowAnyException();
        // Still asked, which is the point: the verdict is what the rollout is watching.
        server.verify();
    }

    @Test
    void observingLetsAWrongActionThrough() {
        TurnstileService service = service(observing());
        respond("{\"success\":true,\"action\":\"login\",\"hostname\":\"rekordo.example.test\"}");

        assertThatCode(() -> service.verify("a-token", ChallengeAction.FORGOT_PASSWORD))
                .doesNotThrowAnyException();
    }

    @Test
    void observingLetsARequestThroughWhenSiteverifyIsUnreachable() {
        TurnstileService service = service(observing());
        server.expect(requestTo(VERIFY_URL)).andRespond(withServerError());

        assertThatCode(() -> service.verify("a-token", ChallengeAction.LOGIN)).doesNotThrowAnyException();
    }

    /**
     * What the clients read to decide whether to gate their submit button. Observing has to
     * report false, or a widget that failed to load would block a submit this server was
     * going to accept anyway -- the outage the state exists to avoid.
     */
    @Test
    void reportsWhetherItWillActuallyRefuse() {
        assertThat(service(configured()).enforced()).isTrue();
        assertThat(service(observing()).enforced()).isFalse();
        assertThat(service(properties("", "", Set.of(), true)).enforced()).isFalse();
    }

    /** No hostname list configured skips the check rather than refusing everything. */
    @Test
    void acceptsAnyHostWhenNoneAreConfigured() {
        TurnstileService service = service(properties("0x-site-key", "0x-secret-key", Set.of(), true));
        respond("{\"success\":true,\"action\":\"login\",\"hostname\":\"anything.example.test\"}");

        assertThatCode(() -> service.verify("a-token", ChallengeAction.LOGIN)).doesNotThrowAnyException();
    }
}
