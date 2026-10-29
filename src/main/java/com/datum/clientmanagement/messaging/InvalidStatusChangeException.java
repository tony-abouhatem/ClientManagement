package com.datum.clientmanagement.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * A status-change message that can never succeed: malformed, incomplete, wrong eventType, invalid status or unknown
 * customer (ticket 07 AC-4, AC-5). It is not retried and goes straight to the DLQ (NFR-9). The message never holds
 * a CPF.
 */
public class InvalidStatusChangeException extends AmqpRejectAndDontRequeueException {

    public InvalidStatusChangeException(String message) {
        super(message);
    }
}
