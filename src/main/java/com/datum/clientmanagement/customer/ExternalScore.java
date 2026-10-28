package com.datum.clientmanagement.customer;

/**
 * Body of the external {@code GET /scores/{cpf}}. {@code score} is boxed so a missing value is detectable;
 * {@code classification} is passed through as-is, so unknown values are accepted.
 */
public record ExternalScore(String cpf, Integer score, String classification) {
}
