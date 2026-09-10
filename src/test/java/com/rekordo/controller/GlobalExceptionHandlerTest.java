package com.rekordo.controller;

import com.rekordo.controller.v1.implementation.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A rejected body has to say which field it rejected.
 *
 * <p>Spring's stock answer for a failed {@code @Valid} is {@code "Invalid request
 * content."} for every cause, so a password one character short and an address with no
 * {@code @} in it are indistinguishable to the caller — which is how a sign-up form ends
 * up showing "something went wrong" for a rule it could have named. These tests pin both
 * halves of the answer: the readable sentence, and the per-field map the apps translate.
 *
 * <p>The controller's collaborators are never reached — validation rejects the body before
 * the method runs — which is why nulls are enough to stand one up here.
 */
class GlobalExceptionHandlerTest {

    /**
     * Stands in for the advice {@code spring.mvc.problemdetails.enabled} registers.
     *
     * <p>It is here because leaving it out is what let a broken build pass: Boot's handler
     * sits at order 0 and answers validation failures too, so an unordered advice of ours
     * is simply never reached and the stock "Invalid request content." goes out no matter
     * what the class under test says. A test without a competitor tests the wrong app.
     */
    @Order(0)
    @RestControllerAdvice
    static class BootsOwnAdvice extends ResponseEntityExceptionHandler {}

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuthController(null, null, null, null, null, null))
            .setControllerAdvice(new BootsOwnAdvice(), new GlobalExceptionHandler())
            .build();

    private static final String CONSENTED = "\"acceptedTerms\":true,\"confirmedAge\":true";

    @Test
    void namesTheFieldWhenThePasswordIsTooShort() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"jonas@example.test\",\"password\":\"test1234\","
                                + "\"displayName\":\"Jonas\"," + CONSENTED + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").value("Use at least 10 characters"))
                .andExpect(jsonPath("$.detail").value("password: Use at least 10 characters"));
    }

    @Test
    void namesTheFieldWhenTheAddressIsNotOne() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"jonas\",\"password\":\"a-long-enough-password\","
                                + "\"displayName\":\"Jonas\"," + CONSENTED + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").doesNotExist());
    }

    @Test
    void namesEveryFieldAtOnce() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"jonas\",\"password\":\"short\",\"displayName\":\"Jonas\","
                                + "\"acceptedTerms\":false,\"confirmedAge\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.acceptedTerms")
                        .value("The terms and the privacy policy have to be accepted"));
    }

    /** The parser's own message quotes the body back, and the body here holds a password. */
    @Test
    void doesNotEchoAnUnreadableBody() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(APPLICATION_JSON)
                        .content("{\"password\":\"hunter2-and-then-some\",\"acceptedTerms\":\"yes\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request body is not valid JSON for this endpoint."));
    }
}
