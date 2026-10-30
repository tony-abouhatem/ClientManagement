package com.datum.clientmanagement.model.enums;

/** Why RabbitMQ did not confirm a publish; stored in {@code outbox_events.last_error_type} (ticket 06 AC-6). */
public enum PublishFailureReason {

    /** No connection or channel to the broker, or any other AMQP exception while sending. */
    BROKER_DOWN,

    /** No positive confirm was obtained: the wait timed out, was interrupted or the confirm future failed. */
    CONFIRM_TIMEOUT,

    /** The broker rejected the message (negative confirm). */
    NACK,

    /** The broker confirmed but returned the message because no queue was bound for it (mandatory return). */
    UNROUTABLE
}
