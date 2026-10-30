package com.datum.clientmanagement.customer;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Small text helpers for customer data: CPF normalizing and masking, and the searchable form of a name.
 * {@link #maskCpf} is injected into the services that log CPFs (NFR-3).
 */
@Component
public class CustomerFormats {

    // Static: read by the static normalizeCpf and searchableName below.
    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final int VISIBLE_CPF_DIGITS = 2;
    private static final String MASKED_CPF_PREFIX = "*********";

    /**
     * Reduces any CPF input ({@code 529.982.247-25}, {@code " 529 982 247/25 "}) to its digits by removing every
     * character that is not 0-9. Validation then checks the length and the check digits. {@code null} stays null.
     */
    // Static: called from the request records' compact constructors (CustomerRequests), which can't be injected.
    public static String normalizeCpf(String cpf) {
        if (cpf == null) {
            return null;
        }
        return NON_DIGITS.matcher(cpf).replaceAll("");
    }

    /** Masks a CPF for logs (NFR-3): only the last 2 digits stay visible, e.g. {@code *********25}. */
    public String maskCpf(String cpf) {
        String digits = normalizeCpf(cpf);
        if (digits == null || digits.length() <= VISIBLE_CPF_DIGITS) {
            return MASKED_CPF_PREFIX;
        }
        return MASKED_CPF_PREFIX + digits.substring(digits.length() - VISIBLE_CPF_DIGITS);
    }

    /**
     * The lowercased, accent-free form of a name stored in {@code name_normalized} (ADR-7) for searching,
     * e.g. {@code "Élise Março"} becomes {@code "elise marco"}. The displayed name is not changed.
     */
    // Static: called from the Customer entity and the CustomerFilter.fromQuery factory, which can't be injected.
    public static String searchableName(String name) {
        // Lowercase first: some uppercase letters lowercase into a base letter plus a combining mark.
        String decomposed = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("");
    }
}
