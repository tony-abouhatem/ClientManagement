package com.datum.clientmanagement.customer;

/** Response of {@code GET /customers/{id}/score}. */
public record ScoreResponse(long customerId, String cpf, int score, String classification) {

    static ScoreResponse from(Customer customer, ExternalScore score) {
        return new ScoreResponse(customer.getId(), customer.getCpf(), score.score(), score.classification());
    }
}
