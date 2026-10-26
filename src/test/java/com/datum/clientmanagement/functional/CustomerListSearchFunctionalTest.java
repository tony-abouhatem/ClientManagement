/*
 * Ticket: docs/backlog/04-list-search-filter.md
 *
 * Interfaces under test (do not exist yet):
 *   - GET /customers?status=&name=&page=&size=&sort=
 *   - GET /customers/search?name=&page=&size=&sort=
 *   - Response shape: {"content":[CustomerResponse...], "page":{"size","number","totalElements","totalPages"}}
 *
 * All requests use a USER jwt(). Data is seeded per test in the Testcontainers Postgres.
 */
package com.datum.clientmanagement.functional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CustomerListSearchFunctionalTest {

    // AC-1 (ticket 04) / NFR-14
    // Given 25 customers
    // When GET /customers (no parameters)
    // Then the response is 200 with content.length == 20
    //  And page == {size:20, number:0, totalElements:25, totalPages:2}
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return the first 20 of 25 customers with page metadata when no parameters are given")
    void ac1_listDefaults_returnsFirstPageOf20() { }

    // AC-1 (ticket 04)
    // Given 25 customers
    // When GET /customers?page=1
    // Then content.length == 5 and page.number == 1
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return the remaining 5 customers when page=1 is requested")
    void ac1_listSecondPage_returnsRemaining() { }

    // AC-2 (ticket 04)
    // Given 3 ACTIVE and 2 INACTIVE customers
    // When GET /customers?status=ACTIVE
    // Then content.length == 3, every item has status "ACTIVE", page.totalElements == 3
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return only ACTIVE customers when status=ACTIVE")
    void ac2_filterByStatus_returnsOnlyMatching() { }

    // AC-3 (ticket 04) / NFR-14
    // Given any data
    // When GET /customers?status=FOO
    // Then the response is 400 problem+json with "status":400
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 problem when status is not a valid value")
    void ac3_invalidStatus_returns400() { }

    // AC-4 (ticket 04)
    // Given customers "João da Silva" and "Maria Souza"
    // When GET /customers/search?name=joao, ?name=JOÃO, ?name=silva and ?name=ão%20d are each called
    // Then each response is 200 and content contains exactly "João da Silva" (not "Maria Souza")
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should find João da Silva when searching case- and accent-insensitively by part of the name")
    void ac4_searchCaseAndAccentInsensitive_returnsMatch() { }

    // AC-5 (ticket 04)
    // Given any data
    // When GET /customers/search (no name), ?name= and ?name=%20%20 are each called
    // Then each response is 400 problem+json with "status":400
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 when search name is missing or blank")
    void ac5_searchMissingOrBlankName_returns400() { }

    // AC-6 (ticket 04)
    // Given "João da Silva" ACTIVE, "João Pereira" INACTIVE, "Maria Souza" INACTIVE
    // When GET /customers?name=joao&status=INACTIVE
    // Then content contains exactly "João Pereira"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should apply name and status filters together when both are given")
    void ac6_nameAndStatusFilters_areAnded() { }

    // AC-7 (ticket 04) / NFR-14 — no clamping: out-of-range paging is rejected
    // Given 25 customers
    // When each of these is called separately on GET /customers:
    //   ?size=0    -> errors[] has exactly 1 entry with field "size"
    //   ?size=-5   -> errors[] has exactly 1 entry with field "size"
    //   ?size=101  -> errors[] has exactly 1 entry with field "size"
    //   ?page=-1   -> errors[] has exactly 1 entry with field "page"
    // Then each response is 400 problem+json with "status":400 and the errors[] entry above (non-empty "message")
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 naming the field when /customers gets size=0, size=-5, size=101 or page=-1")
    void ac7_listOutOfRangePaging_returns400() { }

    // AC-7 (ticket 04) / NFR-14
    // Given customer "João da Silva"
    // When each of these is called separately:
    //   GET /customers/search?name=joao&size=0    -> errors[].field "size"
    //   GET /customers/search?name=joao&size=-5   -> errors[].field "size"
    //   GET /customers/search?name=joao&size=101  -> errors[].field "size"
    //   GET /customers/search?name=joao&page=-1   -> errors[].field "page"
    // Then each response is 400 problem+json with "status":400 and exactly that one errors[] entry
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 naming the field when /customers/search gets size=0, size=-5, size=101 or page=-1")
    void ac7_searchOutOfRangePaging_returns400() { }

    // AC-7 (ticket 04) / NFR-14 — boundaries
    // Given 150 customers
    // When GET /customers?size=1, GET /customers?size=100 and GET /customers?page=0
    // Then the responses are 200 with page.size 1 (content.length 1), page.size 100 (content.length 100),
    //      and page.number 0 / page.size 20 respectively
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should accept size=1, size=100 and page=0 when they are at the allowed boundaries")
    void ac7_pagingBoundaries_areAccepted() { }

    // AC-8 (ticket 04)
    // Given customers created in this order: "Carla", "Ana", "Bruno"
    // When GET /customers?sort=name,asc
    // Then content names are ["Ana", "Bruno", "Carla"]
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should order results by name when sort=name,asc")
    void ac8_sortByName_ordersByName() { }

    // AC-8 (ticket 04)
    // Given customers created in this order: "Carla", "Ana", "Bruno" (ids ascending)
    // When GET /customers (no sort)
    // Then content names are ["Carla", "Ana", "Bruno"] (id ascending)
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should order results by id ascending when no sort is given")
    void ac8_defaultSort_isIdAsc() { }

    // Edge case (ticket 04)
    // Given any data
    // When GET /customers?sort=password,asc
    // Then the response is 400 problem+json
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 400 when sorting by an unknown property")
    void edge_sortByUnknownProperty_returns400() { }

    // AC-9 (ticket 04)
    // Given customer "João da Silva" only
    // When GET /customers/search?name=zzz and GET /customers?name=zzz
    // Then each response is 200 with content == [] and page.totalElements == 0
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should return 200 with empty content when nothing matches")
    void ac9_noMatches_returnsEmptyPage() { }

    // Edge case (ticket 04): SQL wildcards are literal
    // Given customers "Ana Souza" and "50% Off Ltda" and "a_b Comercio"
    // When GET /customers/search?name=%25 (i.e. "%")
    // Then content contains exactly "50% Off Ltda"
    // When GET /customers/search?name=_
    // Then content contains exactly "a_b Comercio"
    @Test
    @Disabled("pending: implementer")
    @DisplayName("should match % and _ literally when they appear in the search name")
    void edge_sqlWildcardsMatchedLiterally() { }
}
