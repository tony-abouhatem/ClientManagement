package com.datum.clientmanagement.validation;

import jakarta.validation.groups.Default;

/**
 * Validation group of {@code POST /customers}. Extends {@link Default} so the rules shared by both endpoints
 * (declared without a group) run too.
 */
public interface OnCreate extends Default {
}
