/*
 * NFR-13, NFR-14 (docs/PRODUCT.md); tickets 02, 03, 04, 05 (GitHub issues #3, #4, #5, #6).
 *
 * Interfaces under test: @RestControllerAdvice in com.datum.clientmanagement.common.error, all /customers endpoints.
 * ProblemDetail fields: type, title, status, detail, instance [, errors[{field, message}]].
 */
package com.datum.clientmanagement.nonfunctional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiContractNfrTest {

    // NFR-13:api
    // Given requests that produce each error status:
    //   400 (POST /customers with cpf "52998224726"), 401 (no token), 403 (USER POST), 404 (GET /customers/999999),
    //   409 (duplicate cpf 52998224725), 422 (PUT with different cpf), 500 (forced failure),
    //   502/503/504 (Score via WireMock)
    // When each response is inspected
    // Then Content-Type is "application/problem+json"
    //  And the body has "type" (string), "title" (non-empty), "status" equal to the HTTP status,
    //      "detail" (string) and "instance" equal to the request path (e.g. "/customers/999999")
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return an RFC 7807 ProblemDetail with all standard fields for every error status")
    void everyError_isProblemDetail() { }

    // NFR-13:api
    // Given an ADMIN
    // When POST /customers {"name":"","cpf":"123","email":"x"}
    // Then the 400 body has errors[] of length 3, each element with non-empty "field" and "message",
    //      and the fields are exactly {"name","cpf","email"}
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should include errors[] with field and message when validation fails")
    void validationErrors_includeFieldAndMessage() { }

    // NFR-14:api — no clamping
    // Given 30 customers
    // When GET /customers?size=1000 and GET /customers/search?name=a&size=1000
    // Then each response is 400 problem+json ("status":400, "instance" == request path)
    //  And errors[] has exactly 1 entry with field "size" and a non-empty message
    //  And no page of data is returned (body has no "content")
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should reject an oversized page with a 400 ProblemDetail instead of clamping it")
    void oversizedPage_isRejectedNotClamped() { }

    // NFR-14:api
    // Given 30 customers
    // When GET /customers?page=-1&size=0
    // Then the response is 400 problem+json with exactly 2 errors[] entries: field "page" and field "size"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should report both page and size in errors[] when both are out of range")
    void invalidPageAndSize_reportBothFields() { }

    // NFR-14:api
    // Given 30 customers
    // When GET /customers (no page/size)
    // Then the response is 200 with page.number == 0, page.size == 20, content.length == 20
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should default to page 0 and size 20 on list when paging parameters are omitted")
    void listDefaultsPage0Size20() { }

    // NFR-14:api
    // Given 30 customers matching "a"
    // When GET /customers/search?name=a (no page/size)
    // Then the response is 200 with page.number == 0, page.size == 20 and content.length == 20
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should default the page size to 20 on search when size is omitted")
    void searchDefaultSize20() { }

    // NFR-14:api
    // Given any data
    // When GET /customers?status=active (lowercase) and GET /customers?status=
    // Then the response is 400 problem+json
    // (Assumes status matching is case-sensitive and an empty value is invalid — flagged in report.)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 when status has an invalid value such as lowercase or empty")
    void invalidStatusVariants_return400() { }
}
