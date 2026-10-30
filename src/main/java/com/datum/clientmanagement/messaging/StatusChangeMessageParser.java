package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.dto.StatusChangeRequest;
import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import com.datum.clientmanagement.model.enums.CustomerStatus;
import com.datum.clientmanagement.model.enums.EventType;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Turns a raw CUSTOMER_STATUS_CHANGE message into a {@link StatusChangeRequest}, or fails with
 * {@link InvalidStatusChangeException}. The body is always read as JSON, whatever the content_type header says (or
 * when it is missing), because upstream systems and the RabbitMQ UI often leave it out.
 */
@Component
public class StatusChangeMessageParser {

    private final ObjectReader reader;

    public StatusChangeMessageParser(JsonMapper jsonMapper) {
        // A strict copy for this message only; the shared mapper serves the REST API and stays untouched.
        this.reader = jsonMapper.rebuild()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .build()
                .readerFor(WireMessage.class);
    }

    public StatusChangeRequest parse(Message message) {
        WireMessage body = read(message.getBody());
        if (body == null) {
            throw new InvalidStatusChangeException("Body is not a JSON object.");
        }
        if (body.eventType() != EventType.CUSTOMER_STATUS_CHANGE) {
            throw new InvalidStatusChangeException("eventType must be " + EventType.CUSTOMER_STATUS_CHANGE + ".");
        }
        if (body.eventId() == null || body.customerId() == null || body.status() == null) {
            throw new InvalidStatusChangeException("eventId, customerId and status are required.");
        }
        return new StatusChangeRequest(body.eventId(), body.customerId(), body.status());
    }

    private WireMessage read(byte[] body) {
        try {
            return reader.readValue(body);
        } catch (JacksonException exception) {
            // Jackson's message can quote the body, so it is not passed on.
            throw new InvalidStatusChangeException("Body is not a valid " + EventType.CUSTOMER_STATUS_CHANGE
                    + " JSON object.");
        }
    }

    private record WireMessage(UUID eventId, EventType eventType, Long customerId, CustomerStatus status) {
    }
}
