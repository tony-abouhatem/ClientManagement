package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.CustomerStatus;
import com.datum.clientmanagement.validation.OnCreate;
import com.datum.clientmanagement.validation.OnUpdate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerRequestValidationTest {

	private static final String VALID_CPF = "52998224725";
	private static final String VALID_EMAIL = "ana@example.com";
	private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();

	private final Validator validator = VALIDATOR_FACTORY.getValidator();

	@AfterAll
	static void closeFactory() {
		VALIDATOR_FACTORY.close();
	}

	@Test
	void createRequest_isNormalizedOnConstruction() {
		CustomerRequest request =
				new CustomerRequest("  João da Silva  ", " 529.982.247-25 ", "  " + VALID_EMAIL + "\t", null, null);

		assertThat(request.name()).isEqualTo("João da Silva");
		assertThat(request.cpf()).isEqualTo(VALID_CPF);
		assertThat(request.email()).isEqualTo(VALID_EMAIL);
		assertThat(invalidFieldsOnCreate(request)).isEmpty();
	}

	@Test
	void updateRequest_isNormalizedOnConstruction() {
		CustomerRequest request = new CustomerRequest(" Ana ", "529/982/247-25", " " + VALID_EMAIL + " ", "ACTIVE", 0L);

		assertThat(request.name()).isEqualTo("Ana");
		assertThat(request.email()).isEqualTo(VALID_EMAIL);
		assertThat(request.cpf()).isEqualTo(VALID_CPF);
		assertThat(invalidFieldsOnUpdate(request)).isEmpty();
	}

	@Test
	void request_nullTextFields_stayNull() {
		CustomerRequest request = new CustomerRequest(null, null, null, null, null);

		assertThat(request.name()).isNull();
		assertThat(request.cpf()).isNull();
		assertThat(request.email()).isNull();
	}

	@Test
	void createRequest_withoutStatus_defaultsToActive() {
		assertThat(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, null, null).statusOrDefault())
				.isEqualTo(CustomerStatus.ACTIVE);
		assertThat(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, "INACTIVE", null).statusOrDefault())
				.isEqualTo(CustomerStatus.INACTIVE);
	}

	@Test
	void createRequest_nameBoundaries() {
		assertThat(invalidFieldsOnCreate(new CustomerRequest("a".repeat(150), VALID_CPF, VALID_EMAIL, null, null)))
				.isEmpty();
		assertThat(invalidFieldsOnCreate(new CustomerRequest("a".repeat(151), VALID_CPF, VALID_EMAIL, null, null)))
				.containsExactly("name");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("   ", VALID_CPF, VALID_EMAIL, null, null)))
				.containsExactly("name");
		assertThat(invalidFieldsOnCreate(new CustomerRequest(null, VALID_CPF, VALID_EMAIL, null, null)))
				.containsExactly("name");
	}

	@Test
	void createRequest_invalidOrMissingFields_areReported() {
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", null, VALID_EMAIL, null, null)))
				.containsExactly("cpf");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", "52998224726", VALID_EMAIL, null, null)))
				.containsExactly("cpf");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, null, null, null)))
				.containsExactly("email");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, "not-an-email", null, null)))
				.containsExactly("email");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, "FOO", null)))
				.containsExactly("status");
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, "active", null)))
				.containsExactly("status");
	}

	@Test
	void createRequest_emailOverMaxLength_isRejected() {
		String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";

		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, longEmail, null, null)))
				.containsExactly("email");
	}

	@Test
	void createRequest_withoutStatusOrVersion_isValid() {
		// status and version are required only by the OnUpdate group: POST defaults the status and ignores version.
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, null, null))).isEmpty();
		assertThat(invalidFieldsOnCreate(new CustomerRequest("Ana", VALID_CPF, VALID_EMAIL, null, 5L))).isEmpty();
	}

	@Test
	void updateRequest_valid_withOrWithoutCpf() {
		// cpf is required only by the OnCreate group.
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("Ana", null, VALID_EMAIL, "ACTIVE", 0L))).isEmpty();
		CustomerRequest withMaskedCpf = new CustomerRequest("Ana", "529.982.247-25", VALID_EMAIL, "INACTIVE", 3L);

		assertThat(invalidFieldsOnUpdate(withMaskedCpf)).isEmpty();
		assertThat(withMaskedCpf.cpf()).isEqualTo(VALID_CPF);
		assertThat(withMaskedCpf.statusOrDefault()).isEqualTo(CustomerStatus.INACTIVE);
	}

	@Test
	void updateRequest_missingVersionAndStatus_areReported() {
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("Ana", null, VALID_EMAIL, null, null)))
				.containsExactlyInAnyOrder("status", "version");
	}

	@Test
	void updateRequest_invalidFields_areReported() {
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("", "123", "bad", "FOO", 0L)))
				.containsExactlyInAnyOrder("name", "email", "status", "cpf");
	}

	@Test
	void updateRequest_sharedRulesStillApply() {
		// OnUpdate extends Default, so the rules declared without a group run on PUT too.
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("a".repeat(151), null, VALID_EMAIL, "ACTIVE", 0L)))
				.containsExactly("name");
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("Ana", null, null, "ACTIVE", 0L)))
				.containsExactly("email");
		assertThat(invalidFieldsOnUpdate(new CustomerRequest("Ana", "52998224726", VALID_EMAIL, "ACTIVE", 0L)))
				.containsExactly("cpf");
	}

	private Set<String> invalidFieldsOnCreate(CustomerRequest request) {
		return invalidFields(validator.validate(request, OnCreate.class));
	}

	private Set<String> invalidFieldsOnUpdate(CustomerRequest request) {
		return invalidFields(validator.validate(request, OnUpdate.class));
	}

	private Set<String> invalidFields(Set<ConstraintViolation<CustomerRequest>> violations) {
		return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
	}
}
