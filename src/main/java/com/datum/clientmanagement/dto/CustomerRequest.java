package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.CustomerStatus;
import com.datum.clientmanagement.util.CustomerFormats;
import com.datum.clientmanagement.validation.Cpf;
import com.datum.clientmanagement.validation.OnCreate;
import com.datum.clientmanagement.validation.OnUpdate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * Request body of {@code POST /customers} ({@link OnCreate}) and {@code PUT /customers/{id}} ({@link OnUpdate}).
 * Rules without a group apply to both endpoints; the group decides what else is required. On construction the name
 * and email are trimmed and the CPF is reduced to its digits, so what is validated is what gets stored.
 * <ul>
 *   <li>{@code cpf} is required on POST. On PUT it is optional; when present it must match the stored CPF because
 *   the CPF is immutable.</li>
 *   <li>{@code status} is optional on POST (defaults to ACTIVE) and required on PUT.</li>
 *   <li>{@code version} is required on PUT: the one the client last read (optimistic lock, ADR-11). POST ignores
 *   it.</li>
 * </ul>
 */
// springdoc ignores validation groups, so both endpoints share this schema: only the fields required on both are
// marked required; the per-endpoint rules are in the descriptions.
@Schema(description = "Customer data. POST requires name, cpf and email (status defaults to ACTIVE, version is "
		+ "ignored). PUT requires name, email, status and the version last read; cpf is optional and cannot change.")
public record CustomerRequest(
	@NotBlank
	@Size(max = CustomerFormats.NAME_MAX_LENGTH)
	String name,

	@Schema(description = "Required on POST; optional on PUT, where it must equal the stored CPF", requiredMode = NOT_REQUIRED)
	@NotNull(groups = OnCreate.class)
	@Cpf
	String cpf,

	@NotBlank
	@Email
	@Size(max = CustomerFormats.EMAIL_MAX_LENGTH)
	String email,

	@Schema(description = "Optional on POST (defaults to ACTIVE); required on PUT", requiredMode = NOT_REQUIRED)
	@NotNull(groups = OnUpdate.class)
	@Pattern(regexp = CustomerStatus.PATTERN, message = CustomerStatus.INVALID_MESSAGE)
	String status,

	@Schema(description = "Required on PUT: the version last read (optimistic locking); ignored on POST", requiredMode = NOT_REQUIRED)
	@NotNull(groups = OnUpdate.class)
	Long version
) {

	public CustomerRequest {
		name = name == null ? null : name.strip();
		email = email == null ? null : email.strip();
		cpf = CustomerFormats.normalizeCpf(cpf);
	}

	/** The requested status, or ACTIVE when none was sent (only possible on POST, where status is optional). */
	public CustomerStatus statusOrDefault() {
		return status == null ? CustomerStatus.ACTIVE : CustomerStatus.valueOf(status);
	}
}
