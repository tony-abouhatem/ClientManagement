package com.datum.clientmanagement.dto;

/** One entry of the {@code errors[]} array in a 400 validation ProblemDetail (NFR-13). */
public record FieldValidationError(
	String field,
	String message
) {
}
