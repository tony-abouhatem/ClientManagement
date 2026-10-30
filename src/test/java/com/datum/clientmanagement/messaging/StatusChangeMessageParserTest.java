package com.datum.clientmanagement.messaging;

import com.datum.clientmanagement.dto.StatusChangeRequest;
import com.datum.clientmanagement.exception.InvalidStatusChangeException;
import com.datum.clientmanagement.model.enums.CustomerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusChangeMessageParserTest {

	private static final UUID EVENT_ID = UUID.fromString("3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60");

	private final StatusChangeMessageParser parser = new StatusChangeMessageParser(JsonMapper.builder().build());

	@Test
	void parse_validMessage_returnsTheRequest() {
		StatusChangeRequest request = parser.parse(message("{\"eventId\":\"" + EVENT_ID
				+ "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":123,\"status\":\"INACTIVE\"}"));

		assertThat(request).isEqualTo(new StatusChangeRequest(EVENT_ID, 123L, CustomerStatus.INACTIVE));
	}

	@Test
	void parse_withoutContentTypeHeader_stillReadsJson() {
		MessageProperties properties = new MessageProperties();
		properties.setContentType(null);
		Message message = new Message(validBody("ACTIVE").getBytes(StandardCharsets.UTF_8), properties);

		assertThat(parser.parse(message).status()).isEqualTo(CustomerStatus.ACTIVE);
	}

	@Test
	void parse_unknownExtraFields_areIgnored() {
		String body = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,"
				+ "\"status\":\"ACTIVE\",\"source\":\"upstream\"}";

		assertThat(parser.parse(message(body)).customerId()).isEqualTo(1L);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{not json", "", "[]", "\"text\"", "42", "null"})
	void parse_bodyThatIsNotAJsonObject_isRejected(String body) {
		assertRejected(body);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"ACTIVE\"}",
			"{\"eventId\":null,\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"not-a-uuid\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"ACTIVE\"}",
			"{\"eventId\":42,\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"ACTIVE\"}"})
	void parse_missingOrInvalidEventId_isRejected(String body) {
		assertRejected(body);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":\"1\",\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1.5,\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":99999999999999999999,\"status\":\"ACTIVE\"}"})
	void parse_missingOrInvalidCustomerId_isRejected(String body) {
		assertRejected(body);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"BLOCKED\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":\"inactive\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,\"status\":1}"})
	void parse_missingOrInvalidStatus_isRejected(String body) {
		assertRejected(body);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"customerId\":1,\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"CUSTOMER_CREATED\",\"customerId\":1,\"status\":\"ACTIVE\"}",
			"{\"eventId\":\"3f1c2a9e-6b1d-4f6e-9a0b-1c2d3e4f5a60\",\"eventType\":\"customer_status_change\",\"customerId\":1,\"status\":\"ACTIVE\"}"})
	void parse_missingOrOtherEventType_isRejected(String body) {
		assertRejected(body);
	}

	private void assertRejected(String body) {
		assertThatThrownBy(() -> parser.parse(message(body))).isInstanceOf(InvalidStatusChangeException.class);
	}

	private static String validBody(String status) {
		return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"CUSTOMER_STATUS_CHANGE\",\"customerId\":1,"
				+ "\"status\":\"" + status + "\"}";
	}

	private static Message message(String body) {
		return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
	}
}
