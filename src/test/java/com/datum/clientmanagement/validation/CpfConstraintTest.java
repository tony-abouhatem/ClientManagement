package com.datum.clientmanagement.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Cpf} delegates to Hibernate Validator's CPF check; these pin the behaviour the API relies on. */
class CpfConstraintTest {

	private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

	private final Validator validator = VALIDATOR_FACTORY.getValidator();

	private record Holder(@Cpf String cpf) {
	}

	@AfterAll
	static void closeFactory() {
		VALIDATOR_FACTORY.close();
	}

	@ParameterizedTest
	@ValueSource(strings = {"52998224725", "11144477735", "39053344705", "93541134780"})
	void validCpf_isAccepted(String cpf) {
		assertThat(violations(cpf)).isEmpty();
	}

	@Test
	void null_isAcceptedSoNotNullDecides() {
		assertThat(violations(null)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"52998224726", "52998224715", "11144477736"})
	void wrongCheckDigits_areRejected(String cpf) {
		assertThat(violations(cpf)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"00000000000", "11111111111", "55555555555", "99999999999"})
	void repeatedDigits_areRejected(String cpf) {
		assertThat(violations(cpf)).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "123", "5299822472", "529982247250"})
	void wrongLength_isRejected(String cpf) {
		assertThat(violations(cpf)).hasSize(1);
	}

	@Test
	void rejection_usesTheApiMessage() {
		assertThat(violations("52998224726")).extracting(ConstraintViolation::getMessage)
				.containsExactly("must be a valid CPF (11 digits with valid check digits)");
	}

	private Set<ConstraintViolation<Holder>> violations(String cpf) {
		return validator.validate(new Holder(cpf));
	}
}
