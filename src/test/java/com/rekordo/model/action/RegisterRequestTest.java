package com.rekordo.model.action;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ticks are a validation rule, not a UI nicety.
 *
 * <p>A box that the server would accept unticked is decoration, and the record it leaves is
 * a record of nothing. The null cases are the ones worth pinning: Bean Validation treats a
 * null as satisfying {@code @AssertTrue}, so an older client that simply omits the fields
 * would sail through without the {@code @NotNull} beside it.
 */
class RegisterRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private RegisterRequest request(Boolean acceptedTerms, Boolean confirmedAge) {
        // No Turnstile token: whether one is required is TurnstileService's decision, not a
        // constraint on the record, so its absence must not register as a validation failure.
        return new RegisterRequest(
                "jonas@example.test", "a-long-enough-password", "Jonas", acceptedTerms, confirmedAge, null);
    }

    @Test
    void acceptsBothTicks() {
        assertThat(validator.validate(request(true, true))).isEmpty();
    }

    @Test
    void refusesAnUntickedAgreement() {
        assertThat(validator.validate(request(false, true))).hasSize(1);
    }

    @Test
    void refusesAnUnconfirmedAge() {
        assertThat(validator.validate(request(true, false))).hasSize(1);
    }

    @Test
    void refusesAClientThatOmitsTheFieldsEntirely() {
        assertThat(validator.validate(request(null, null))).hasSize(2);
    }
}
