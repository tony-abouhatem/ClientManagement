package com.datum.clientmanagement.messaging;

/** A publish that RabbitMQ did not confirm, with the {@link PublishFailureReason} it failed for. */
class PublishFailedException extends RuntimeException {

    private final PublishFailureReason reason;

    PublishFailedException(PublishFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    PublishFailedException(PublishFailureReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    PublishFailureReason getReason() {
        return reason;
    }
}
