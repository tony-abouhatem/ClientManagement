package com.datum.clientmanagement.validation;

import com.datum.clientmanagement.util.CustomerFormats;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import org.hibernate.validator.constraints.br.CPF;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is a valid CPF, checked by Hibernate Validator's {@link CPF}: 11 digits, not a repeated-digit
 * sequence, and the last 2 digits match the check digits computed from the first 9 (modulo 11).
 * Apply it to an already normalized value (see {@link CustomerFormats#normalizeCpf}). {@code null} is valid; combine with
 * {@code @NotNull} when the CPF is required.
 */
@CPF
@ReportAsSingleViolation
@Documented
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Cpf {

	String message() default "must be a valid CPF (11 digits with valid check digits)";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
