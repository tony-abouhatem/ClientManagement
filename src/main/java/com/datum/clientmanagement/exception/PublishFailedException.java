package com.datum.clientmanagement.exception;

import com.datum.clientmanagement.model.PublishFailureReason;

/** A publish that RabbitMQ did not confirm, with the {@link PublishFailureReason} it failed for. */
public class PublishFailedException extends RuntimeException {

	private final PublishFailureReason reason;

	public PublishFailedException(PublishFailureReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public PublishFailedException(PublishFailureReason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public PublishFailureReason getReason() {
		return reason;
	}
}
