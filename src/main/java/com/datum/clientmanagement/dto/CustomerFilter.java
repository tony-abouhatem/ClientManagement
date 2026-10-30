package com.datum.clientmanagement.dto;

import com.datum.clientmanagement.model.enums.CustomerStatus;
import com.datum.clientmanagement.util.CustomerFormats;

/**
 * Optional filters of a customer listing; a {@code null} component means "no filter". {@code normalizedName} is
 * already in the searchable form stored in {@code name_normalized} (ADR-7), so it is compared as is.
 */
public record CustomerFilter(
	CustomerStatus status,
	String normalizedName
) {

	/**
	 * Builds the filter from raw query parameters. {@code status} must already be validated against
	 * {@link CustomerStatus#PATTERN}; a blank {@code name} means no name filter.
	 */
	public static CustomerFilter fromQuery(String status, String name) {
		CustomerStatus customerStatus = status == null ? null : CustomerStatus.valueOf(status);
		String normalizedName = name == null || name.isBlank() ? null : CustomerFormats.searchableName(name.strip());
		return new CustomerFilter(customerStatus, normalizedName);
	}
}
