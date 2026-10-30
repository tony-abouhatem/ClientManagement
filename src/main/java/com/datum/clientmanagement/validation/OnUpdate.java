package com.datum.clientmanagement.validation;

import jakarta.validation.groups.Default;

/**
 * Validation group of {@code PUT /customers/{id}}. Extends {@link Default} so the rules shared by both endpoints
 * (declared without a group) run too.
 */
public interface OnUpdate extends Default {
}
