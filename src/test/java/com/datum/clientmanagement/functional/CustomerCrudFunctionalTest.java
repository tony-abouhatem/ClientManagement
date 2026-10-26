/*
 * Ticket: 03 (GitHub issue #4)
 *
 * Interfaces under test (do not exist yet):
 *   - POST /customers, PUT /customers/{id}, DELETE /customers/{id}, GET /customers/{id}
 *   - DTOs (package com.datum.clientmanagement.customer): CreateCustomerRequest(name, cpf, email, status?),
 *     UpdateCustomerRequest(name, email, status, version, cpf?) — version REQUIRED (optimistic lock),
 *     CustomerResponse(id, name, cpf, email, status, createdAt, updatedAt, version)
 *   - com.datum.clientmanagement.common.error @RestControllerAdvice -> ProblemDetail
 *     {type,title,status,detail,instance[,errors[{field,message}]]}, Content-Type application/problem+json
 *
 * All requests use an ADMIN jwt() unless stated. Integration against Testcontainers Postgres.
 * Valid CPFs used: 52998224725, 11144477735, 39053344705, 93541134780.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerCrudFunctionalTest {

    // AC-1 (ticket 03)
    // Given an ADMIN and no customers
    // When POST /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"} (no status)
    // Then the response is 201
    //  And header Location == "/customers/{id}" where {id} == body.id (numeric)
    //  And body == {id:<id>, name:"Ana Souza", cpf:"52998224725", email:"ana@example.com", status:"ACTIVE",
    //               createdAt:<ISO-8601>, updatedAt:<ISO-8601>, version:0}
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 201 with Location and status ACTIVE when an ADMIN creates a valid customer without status")
    void createCustomer_returns201() { }

    // AC-1 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Bruno Lima","cpf":"11144477735","email":"bruno@example.com","status":"INACTIVE"}
    // Then the response is 201 and body.status == "INACTIVE"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should keep the given status when POST includes status INACTIVE")
    void createCustomerWithExplicitStatus_keepsStatus() { }

    // AC-2 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Ana Souza","cpf":"529.982.247-25","email":"ana@example.com"}
    // Then the response is 201 with body.cpf == "52998224725"
    //  And the customers row has cpf == "52998224725"
    //  And GET /customers/{id} returns cpf "52998224725"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should store and return the CPF as 11 digits when a masked CPF is posted")
    void maskedCpf_isNormalized() { }

    // AC-2 (ticket 03) edge case: surrounding spaces / mixed punctuation
    // Given an ADMIN
    // When POST /customers with cpf "  529.982247-25 "
    // Then the response is 201 with body.cpf == "52998224725"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should normalize the CPF when it has surrounding spaces or mixed punctuation")
    void cpfWithSpacesAndMixedPunctuation_isNormalized() { }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers {"name":"Ana Souza","cpf":"52998224726","email":"ana@example.com"} (wrong check digit)
    // Then the response is 400 problem+json, "status":400
    //  And errors[] contains an entry with field == "cpf" and a non-empty message
    //  And no customer row is written
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with errors[].field=cpf when the CPF check digits are invalid")
    void invalidCheckDigits_returns400() { }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers with cpf "11111111111" (also "111.111.111-11" and "00000000000")
    // Then each response is 400 with errors[] containing field == "cpf"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with errors[].field=cpf when the CPF is a repeated-digit sequence")
    void repeatedDigitCpf_returns400() { }

    // AC-3 (ticket 03)
    // Given an ADMIN
    // When POST /customers with cpf "5299822472" (10 digits) and, separately, "529982247250" (12 digits)
    // Then each response is 400 with errors[] containing field == "cpf"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with errors[].field=cpf when the CPF does not have 11 digits")
    void wrongLengthCpf_returns400() { }

    // AC-4 (ticket 03) / NFR-13
    // Given an ADMIN
    // When POST /customers is called with each single invalid field (others valid, cpf 52998224725):
    //   name "   "                     -> errors[].field "name"
    //   name of 151 chars ("a" x 151)  -> errors[].field "name"
    //   email "not-an-email"           -> errors[].field "email"
    //   email omitted                  -> errors[].field "email"
    //   status "FOO"                   -> errors[].field "status"
    // Then each response is 400 problem+json with exactly 1 errors[] entry for the named field
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with one error for the invalid field when POST has an invalid name, email or status")
    void postInvalidSingleField_returns400() { }

    // AC-4 (ticket 03) boundary
    // Given an ADMIN
    // When POST /customers with name of exactly 150 chars ("a" x 150), cpf 39053344705, email "max@example.com"
    // Then the response is 201
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should accept the customer when the name has exactly 150 characters")
    void nameAt150Chars_isAccepted() { }

    // AC-4 (ticket 03) / NFR-13
    // Given an ADMIN
    // When POST /customers {"name":"","cpf":"52998224725","email":"bad"} is called
    // Then the response is 400 with exactly 2 errors[] entries: field "name" and field "email"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return one errors[] entry per invalid field when several fields are invalid")
    void multipleInvalidFields_returnsOneErrorPerField() { }

    // AC-4 (ticket 03)
    // Given an existing customer id X (cpf 52998224725) at version 0
    // When PUT /customers/X {"name":"","email":"bad","status":"FOO","version":0}
    // Then the response is 400 with errors[] entries for fields "name", "email", "status"
    //  And the stored customer is unchanged
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with one error per invalid field when PUT has invalid name, email and status")
    void putInvalidFields_returns400() { }

    // AC-5 (ticket 03)
    // Given an existing customer with cpf "52998224725"
    // When POST /customers {"name":"Outra Pessoa","cpf":"52998224725","email":"outra@example.com"}
    //  and, separately, the same body with cpf "529.982.247-25"
    // Then each response is 409 problem+json, "status":409
    //  And only one customers row has cpf 52998224725
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 409 when POST uses an existing CPF, masked or raw")
    void duplicateCpf_returns409() { }

    // AC-5 (ticket 03)
    // Given no customer with cpf "93541134780"
    // When 2 threads concurrently POST /customers with cpf "93541134780" (different names/emails)
    // Then exactly one response is 201 and the other is 409 (never 500)
    //  And exactly one customers row has cpf 93541134780
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 409 for the loser when two concurrent POSTs use the same CPF")
    void concurrentDuplicateCpf_returns409ForLoser() { }

    // Edge case (ticket 03): email is not unique
    // Given an existing customer with email "shared@example.com"
    // When POST /customers {"name":"Carla Dias","cpf":"11144477735","email":"shared@example.com"}
    // Then the response is 201
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should allow creation when the email is already used by another customer")
    void duplicateEmail_isAllowed() { }

    // Edge case (ticket 03): name is trimmed
    // Given an ADMIN
    // When POST /customers with name "  João da Silva  ", cpf 39053344705
    // Then body.name == "João da Silva"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should trim the name when it has surrounding spaces")
    void nameIsTrimmed() { }

    // AC-6 (ticket 03)
    // Given customer X {name:"Ana Souza", cpf:"52998224725", email:"ana@example.com", status:ACTIVE, version:0} with updatedAt T0
    // When PUT /customers/X {"name":"Ana S. Lima","email":"ana.lima@example.com","status":"INACTIVE","version":0} (no cpf)
    // Then the response is 200 with name "Ana S. Lima", email "ana.lima@example.com", status "INACTIVE",
    //      cpf "52998224725", version 1, createdAt unchanged, updatedAt > T0
    //  And GET /customers/X returns the same values
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 with updated fields and a later updatedAt when PUT omits the CPF")
    void updateWithoutCpf_returns200() { }

    // AC-6 (ticket 03)
    // Given customer X with cpf "52998224725" at version 0
    // When PUT /customers/X {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","cpf":"529.982.247-25","version":0}
    // Then the response is 200 with cpf "52998224725" (same CPF after normalization counts as unchanged)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 when PUT sends the same CPF in masked form")
    void updateWithUnchangedMaskedCpf_returns200() { }

    // AC-7 (ticket 03)
    // Given customer X with cpf "52998224725", name "Ana Souza", version 0
    // When PUT /customers/X {"name":"Changed","email":"x@example.com","status":"INACTIVE","cpf":"11144477735","version":0}
    // Then the response is 422 problem+json, "status":422
    //  And GET /customers/X still returns name "Ana Souza", cpf "52998224725", original email and status, "version":0
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 422 and change nothing when PUT sends a different CPF")
    void updateWithDifferentCpf_returns422() { }

    // AC-8 (ticket 03)
    // Given no customer with id 999999
    // When GET /customers/999999,
    //      PUT /customers/999999 {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE","version":0}
    //      and DELETE /customers/999999 are called
    // Then each response is 404 problem+json with "status":404 and "title":"Customer not found"
    //  (same title for every customer 404 — confirmed by the user)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 404 problem when GET, PUT or DELETE targets a nonexistent id")
    void unknownId_returns404() { }

    // AC-8 (ticket 03) — happy-path read
    // Given customer X created with cpf 52998224725
    // When GET /customers/X (as USER)
    // Then the response is 200 with {id:X, name, cpf:"52998224725", email, status, createdAt, updatedAt, version:0}
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 with the customer when GET targets an existing id")
    void getById_existingCustomer_returns200() { }

    // AC-9 (ticket 03)
    // Given customer X exists
    // When DELETE /customers/X
    // Then the response is 204 with empty body
    //  And a subsequent GET /customers/X returns 404
    //  And no customers row with id X remains (hard delete)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 204 and make the customer unreachable when DELETE targets an existing id")
    void delete_returns204ThenGetReturns404() { }

    // AC-10 (ticket 03)
    // Given an ADMIN
    // When POST /customers with body "{\"name\": \"Ana\", " (truncated JSON), Content-Type application/json
    // Then the response is 400 problem+json, "status":400
    //  And the body does not contain "Exception", "at com.", "at org." or "trace"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 problem without stack trace when the JSON body is malformed")
    void malformedJson_returns400() { }

    // AC-10 (ticket 03)
    // Given an ADMIN
    // When GET /customers/abc (and PUT /customers/abc, DELETE /customers/abc)
    // Then each response is 400 problem+json, "status":400, with no stack trace in the body
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 problem when the id is not numeric")
    void nonNumericId_returns400() { }

    // AC-11 (ticket 03) / NFR-12 — deterministic stale update
    // Given customer X created with name "Ana Souza", cpf 52998224725, email "ana@example.com"
    //  And GET /customers/X returns "version":0
    // When PUT /customers/X {"name":"Name A","email":"ana@example.com","status":"ACTIVE","version":0}
    // Then the response is 200 with name "Name A" and "version":1
    // When PUT /customers/X {"name":"Name B","email":"ana@example.com","status":"ACTIVE","version":0} (stale)
    // Then the response is 409 problem+json ("status":409)
    //  And GET /customers/X returns name "Name A" and "version":1 (stale update not applied)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 409 and keep the current data when PUT sends a stale version")
    void staleVersion_returns409() { }

    // AC-11 (ticket 03) / NFR-12
    // Given customer X at version 0
    // When PUT /customers/X {"name":"Ana Souza","email":"ana@example.com","status":"ACTIVE"} (no "version")
    // Then the response is 400 problem+json with exactly 1 errors[] entry, field "version"
    //  And GET /customers/X still returns "version":0
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 with errors[].field=version when PUT omits the version")
    void putWithoutVersion_returns400() { }

    // AC-11 (ticket 03) / NFR-12 — race where both requests pass the application-level version check
    // Given customer X at version 0
    // When 2 threads concurrently PUT /customers/X, both with "version":0
    //      (A: name "Name A", B: name "Name B"; both email "a@example.com", status ACTIVE)
    // Then exactly one response is 200 and the other is 409 problem+json (never 500, never two 200s)
    //  And the stored name is the winner's value and "version" is 1
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 409 for one of two concurrent updates that both send the same version")
    void concurrentUpdatesSameVersion_oneReturns409() { }

    // Edge case (ticket 03)
    // Given an unexpected RuntimeException is thrown while handling GET /customers/{id}
    // When the request completes
    // Then the response is 500 problem+json, "status":500, with a generic detail
    //  And the body does not contain the exception message, class name or stack frames
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return a generic 500 problem without internals when an unexpected exception occurs")
    void unexpectedException_returnsGeneric500() { }
}
