/*
 * Ticket: 08 (GitHub issue #9) — README finalization, OpenAPI and Postman.
 *
 * Interfaces under test:
 *   - README.md (repo root, Portuguese): "## " section headings, the "Configurações necessárias" table,
 *     the WireMock CPF table and the RabbitMQ section
 *   - src/main/resources/application.yaml: every ${VAR:default} placeholder (read from the file, never hard-coded)
 *   - docker/wiremock/mappings/*.json: the CPFs in request.url "/scores/{cpf}" (read from the files)
 *   - postman/ClientManagement.postman_collection.json (Postman collection v2.1, does not exist yet)
 *   - postman/ClientManagement.postman_environment.json (Postman environment, does not exist yet)
 *   - GET /v3/api-docs (springdoc OpenAPI 3 JSON, anonymous): every CustomerController operation, request/response
 *     schemas, error responses and a bearer-JWT security scheme. springdoc config/annotations do not exist yet.
 *
 * Triage decisions (accepted 2026-10-28): the README and Postman checks are structural only. Manual checks, not
 * automated here: README content verified against the running stack (AC-1, AC-5) and running the collection in
 * Postman/newman (AC-3). The anonymous /swagger-ui.html redirect (NFR-2) is in nonfunctional/SecurityNfrTest.
 *
 * Like FoundationFunctionalTest, the static file checks run without Docker; only the nested RunningApplication
 * class needs the Spring context (Testcontainers) and is skipped without Docker. File paths are relative to the
 * repo root (the Maven working directory).
 *
 * Operation contract used below (from CustomerController):
 *   POST /customers, GET /customers, GET /customers/search, GET /customers/{id}, GET /customers/{id}/score,
 *   PUT /customers/{id}, DELETE /customers/{id}
 */
package com.datum.clientmanagement.functional;

import com.datum.clientmanagement.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentationFunctionalTest {

    private static final Path README = Path.of("README.md");
    private static final Path APPLICATION_YAML = Path.of("src", "main", "resources", "application.yaml");
    private static final Path WIREMOCK_MAPPINGS = Path.of("docker", "wiremock", "mappings");
    private static final Path POSTMAN_COLLECTION = Path.of("postman", "ClientManagement.postman_collection.json");
    private static final Path POSTMAN_ENVIRONMENT = Path.of("postman", "ClientManagement.postman_environment.json");

    private static final List<String> REQUIRED_SECTIONS = List.of(
            "Arquitetura da solução", "Requisitos para execução",
            "Como iniciar as dependências", "Como iniciar a aplicação", "Como obter e usar o Access Token",
            "Como executar os testes", "Endpoints disponíveis", "Configurações necessárias",
            "Exemplos de utilização das APIs", "Funcionamento da integração com RabbitMQ");
    private static final String CONFIGURATION_SECTION = "Configurações necessárias";
    private static final String RABBITMQ_SECTION = "Funcionamento da integração com RabbitMQ";

    private static final Pattern TBD = Pattern.compile("(?i)\\bTBD\\b");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+):([^}]*)}");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]*)`");
    private static final Pattern SCORE_URL = Pattern.compile("^/scores/(\\d{11})$");

    private static final String TOKEN_PATH = "/realms/client-management/protocol/openid-connect/token";
    private static final String APP_URL = "http://localhost:8080";
    private static final String KEYCLOAK_URL = "http://localhost:8081";
    private static final String RABBIT_MANAGEMENT_URL = "http://localhost:15672";
    private static final String RABBIT_MANAGEMENT_PATH = "/api/";
    private static final String CREATED_QUEUE_GET_OPERATION = "POST /api/queues/%2F/customer.created/get";
    private static final String EVENTS_EXCHANGE_PUBLISH_OPERATION = "POST /api/exchanges/%2F/customer.events/publish";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{(\\w+)}}");
    private static final Pattern HOST_VARIABLE = Pattern.compile("^\\{\\{(\\w+)}}$");
    private static final Pattern ID_SEGMENT = Pattern.compile("\\{\\{\\w+}}|:\\w+|\\d+");
    private static final Pattern STATIC_JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.");
    private static final Pattern TOKEN_STORED = Pattern.compile(
            "pm\\.(environment|collectionVariables)\\.set\\(\\s*['\"](\\w+)['\"]\\s*,[^)]*access_token");
    private static final Pattern ASSERTED_STATUS = Pattern.compile(
            "pm\\.response\\.to\\.have\\.status\\((\\d{3})\\)|pm\\.response\\.code\\b[^;\\n]*?(\\d{3})");
    private static final Set<Integer> ERROR_SCENARIO_STATUSES = new TreeSet<>(List.of(401, 403, 404, 409, 422));
    private static final Set<Integer> SCORE_SCENARIO_STATUSES = new TreeSet<>(List.of(200, 404, 503, 504));
    private static final Map<String, Integer> SCORE_STATUS_BY_CPF = Map.of(
            "52998224725", 200, "11144477735", 404, "39053344705", 503, "93541134780", 504);
    private static final String CREATE_OPERATION = "POST /customers";
    private static final String SCORE_OPERATION = "GET /customers/{id}/score";
    private static final List<String> CUSTOMER_OPERATIONS = List.of(CREATE_OPERATION, "GET /customers",
            "GET /customers/search", "GET /customers/{id}", SCORE_OPERATION, "PUT /customers/{id}",
            "DELETE /customers/{id}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // ---------------------------------------------------------------------------------------------------------------
    // README (AC-1, AC-4, AC-5, edge case)
    // ---------------------------------------------------------------------------------------------------------------

    // AC-1 (ticket 08)
    // Given README.md at the repo root
    // When its "## " headings are collected (trimmed, compared case-insensitively)
    // Then all 10 of these headings are present:
    //      "Arquitetura da solução", "Requisitos para execução", "Como iniciar as dependências",
    //      "Como iniciar a aplicação", "Como obter e usar o Access Token", "Como executar os testes",
    //      "Endpoints disponíveis", "Configurações necessárias", "Exemplos de utilização das APIs",
    //      "Funcionamento da integração com RabbitMQ"
    //  And the assertion message names every missing heading (e.g. "missing: [Endpoints disponíveis]")
    @Test
    @DisplayName("should contain the ten required sections when the README is read")
    void readme_containsRequiredSections() {
        Set<String> headings = readmeSections().keySet();

        List<String> missing = REQUIRED_SECTIONS.stream()
                .filter(section -> !headings.contains(normalizedHeading(section)))
                .toList();

        assertThat(missing).as("missing: %s", missing).isEmpty();
    }

    // AC-1 (ticket 08)
    // Given README.md
    // When each of the 10 required sections is cut from its heading to the next "## " (or "# " outside a code fence)
    // Then every section has at least one non-blank line of content (an empty "## Endpoints disponíveis" fails)
    @Test
    @DisplayName("should have content under every required section when the README is read")
    void readme_requiredSectionsAreNotEmpty() {
        Map<String, List<String>> sections = readmeSections();

        List<String> empty = REQUIRED_SECTIONS.stream()
                .filter(section -> sections.getOrDefault(normalizedHeading(section), List.of()).stream()
                        .allMatch(String::isBlank))
                .toList();

        assertThat(empty).as("empty or missing sections: %s", empty).isEmpty();
    }

    // AC-1 (ticket 08)
    // Given README.md
    // When the whole file is searched for the word "TBD" (case-insensitive, word boundary: \bTBD\b)
    // Then there is no match
    //  (today line 5 contains "Itens marcados **TBD** serão confirmados", so this fails until the README is finalized)
    //  And the assertion message lists the line numbers of any match
    @Test
    @DisplayName("should not contain any TBD marker when the README is finalized")
    void readme_hasNoTbd() {
        List<String> lines = readLines(README);

        List<Integer> linesWithTbd = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            if (TBD.matcher(lines.get(index)).find()) {
                linesWithTbd.add(index + 1);
            }
        }

        assertThat(linesWithTbd).as("README lines containing TBD: %s", linesWithTbd).isEmpty();
    }

    // Edge case (ticket 08): README commands work on Windows (Git Bash / mvnw.cmd) and Linux/macOS
    // Given README.md
    // When the sections "Como iniciar a aplicação" and "Como executar os testes" are read
    // Then each section contains "./mvnw" and also "mvnw.cmd"
    @Test
    @DisplayName("should document both the Unix and the Windows Maven wrapper when the run and test sections are read")
    void readme_documentsMavenWrapperForUnixAndWindows() {
        for (String section : List.of("Como iniciar a aplicação", "Como executar os testes")) {
            assertThat(sectionText(section)).as("section \"%s\"", section).contains("./mvnw", "mvnw.cmd");
        }
    }

    // AC-4 (ticket 08)
    // Given src/main/resources/application.yaml, read as text at test time
    //  And every placeholder matching \$\{([A-Z0-9_]+):([^}]*)\} is collected as VAR -> default
    //      (no hard-coded list; e.g. DB_URL -> "jdbc:postgresql://localhost:5432/clientmanagement",
    //       SERVER_PORT -> "8080", SCORE_CB_SLIDING_WINDOW_SIZE -> "10", PAGINATION_DEFAULT_SIZE -> "20";
    //       a VAR used twice, such as SCORE_CB_SLIDING_WINDOW_SIZE, counts once)
    //  And at least one placeholder is found (guards against a broken regex passing vacuously)
    // When the README table under "## Configurações necessárias" is parsed:
    //      column 1 = backticked names separated by " / ", column 3 = backticked defaults separated by " / ",
    //      matched by position (e.g. "`SCORE_CONNECT_TIMEOUT` / `SCORE_READ_TIMEOUT`" with "`1s` / `2s`")
    // Then every VAR from application.yaml appears in column 1
    //  And its default in column 3 equals the application.yaml default exactly (backticks stripped)
    //  And the assertion message names each missing VAR and each VAR whose default differs
    //      (e.g. "SERVER_PORT: missing", "OUTBOX_BATCH_SIZE: README `50`, application.yaml `100`")
    @Test
    @DisplayName("should list every application.yaml env var with its default when the configuration table is read")
    void readme_configurationTableListsEveryEnvVarWithDefault() {
        Map<String, String> yamlDefaults = applicationYamlDefaults();
        assertThat(yamlDefaults).as("placeholders found in application.yaml").isNotEmpty();
        Map<String, String> readmeDefaults = readmeConfigurationDefaults();

        List<String> problems = new ArrayList<>();
        yamlDefaults.forEach((variable, yamlDefault) -> {
            String readmeDefault = readmeDefaults.get(variable);
            if (readmeDefault == null) {
                problems.add(variable + ": missing");
            } else if (!readmeDefault.equals(yamlDefault)) {
                problems.add("%s: README `%s`, application.yaml `%s`".formatted(variable, readmeDefault, yamlDefault));
            }
        });

        assertThat(problems).as("configuration table problems: %s", problems).isEmpty();
    }

    // AC-4 (ticket 08)
    // Given the README "Configurações necessárias" table
    // When a row has a different number of names in column 1 than defaults in column 3
    //      (e.g. "`A` / `B`" with only "`x`")
    // Then the test fails naming that row, so positional matching never pairs a VAR with the wrong default
    @Test
    @DisplayName("should pair every grouped env var with exactly one default when the configuration table is read")
    void readme_configurationTableRowsHaveOneDefaultPerVar() {
        List<ConfigurationRow> rows = readmeConfigurationRows();
        assertThat(rows).as("rows of the configuration table").isNotEmpty();

        List<String> mismatchedRows = rows.stream()
                .filter(row -> row.names().size() != row.defaults().size())
                .map(ConfigurationRow::line)
                .toList();

        assertThat(mismatchedRows).as("rows whose names and defaults differ in count: %s", mismatchedRows).isEmpty();
    }

    // AC-5 (ticket 08)
    // Given every docker/wiremock/mappings/*.json whose request.url matches "/scores/{cpf}" (read at test time;
    //       today 52998224725, 11144477735, 39053344705, 93541134780)
    //  And at least one such CPF is found
    // When README.md is read
    // Then every one of those CPFs appears in the README (11 digits, unformatted)
    @Test
    @DisplayName("should document every WireMock scenario CPF when the README is read")
    void readme_documentsWireMockScenarioCpfs() {
        Set<String> cpfs = wireMockScenarioCpfs();
        assertThat(cpfs).as("CPFs in the WireMock mappings").isNotEmpty();

        assertThat(readText(README)).contains(cpfs);
    }

    // AC-5 (ticket 08)
    // Given the README section "Funcionamento da integração com RabbitMQ"
    // When it is read
    // Then it contains the RabbitMQ Management URL "http://localhost:15672"
    //  And the exchange "customer.events" and the routing key "customer.status.change"
    //  And the UI action "Publish message"
    //  And an example payload containing "\"eventType\":\"CUSTOMER_STATUS_CHANGE\"" with "eventId", "customerId"
    //      and "status" fields
    @Test
    @DisplayName("should document how to publish a CUSTOMER_STATUS_CHANGE from the RabbitMQ UI when the README is read")
    void readme_documentsStatusChangePublishingFromRabbitUi() {
        String section = sectionText(RABBITMQ_SECTION);

        assertThat(section).contains("http://localhost:15672", "customer.events", "customer.status.change",
                "Publish message");
        assertThat(section.lines().filter(line -> line.contains("\"eventType\":\"CUSTOMER_STATUS_CHANGE\"")))
                .as("example CUSTOMER_STATUS_CHANGE payload")
                .anySatisfy(payload -> assertThat(payload).contains("\"eventId\"", "\"customerId\"", "\"status\""));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Postman (AC-3, structural only)
    // ---------------------------------------------------------------------------------------------------------------

    // AC-3 (ticket 08)
    // Given postman/ClientManagement.postman_collection.json and postman/ClientManagement.postman_environment.json
    // When both are parsed with Jackson
    // Then both parse without error
    //  And the collection's info.schema contains "v2.1.0" and info.name is "ClientManagement"
    //  And the environment has a non-empty "values" array of {key, value} entries
    @Test
    @DisplayName("should provide a valid Postman collection and environment when the postman folder is read")
    void postman_collectionAndEnvironmentAreValidJson() {
        JsonNode collection = readJson(POSTMAN_COLLECTION);
        JsonNode environment = readJson(POSTMAN_ENVIRONMENT);

        assertThat(collection.path("info").path("schema").asString()).contains("v2.1.0");
        assertThat(collection.path("info").path("name").asString()).isEqualTo("ClientManagement");
        JsonNode values = environment.path("values");
        assertThat(values.isArray()).as("environment values is an array").isTrue();
        assertThat(values.values()).isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.has("key") && entry.has("value"))
                        .as("entry %s has key and value", entry).isTrue());
    }

    // AC-3 (ticket 08)
    // Given the collection (items flattened recursively through folders)
    // When requests with method POST and a url containing "/realms/client-management/protocol/openid-connect/token"
    //      are selected
    // Then there are two: one whose x-www-form-urlencoded body has username "user" and one with username "admin"
    //      (the users of docker/keycloak/client-management-realm.json; usernames may be {{variables}} resolving
    //      to those values in the environment)
    //  And both have grant_type "password" and client_id "client-management-cli"
    //  And each has a "test" event script that stores json.access_token in a variable
    //      (pm.environment.set / pm.collectionVariables.set), e.g. "userToken" and "adminToken"
    @Test
    @DisplayName("should fetch USER and ADMIN tokens from Keycloak when the collection is read")
    void postman_fetchesUserAndAdminTokens() {
        Map<String, String> environment = postmanEnvironment();
        List<PostmanRequest> tokenRequests = postmanRequests().stream().filter(PostmanRequest::isTokenRequest).toList();

        assertThat(tokenRequests).hasSize(2);
        assertThat(tokenRequests)
                .extracting(request -> resolve(request.formValue("username"), environment))
                .containsExactlyInAnyOrder("user", "admin");
        Set<String> tokenVariables = new LinkedHashSet<>();
        for (PostmanRequest request : tokenRequests) {
            assertThat(resolve(request.formValue("grant_type"), environment)).as(request.name()).isEqualTo("password");
            assertThat(resolve(request.formValue("client_id"), environment)).as(request.name())
                    .isEqualTo("client-management-cli");
            Matcher stored = TOKEN_STORED.matcher(request.testScript());
            assertThat(stored.find()).as("%s stores access_token in a variable", request.name()).isTrue();
            tokenVariables.add(stored.group(2));
        }
        assertThat(tokenVariables).as("one variable per token").hasSize(2);
    }

    // AC-3 (ticket 08)
    // Given the collection and the environment
    // When the requests are inspected
    // Then every request url host is a {{variable}} (no hard-coded "localhost")
    //  And the environment defines those variables, with the app at "http://localhost:8080" and Keycloak at
    //      "http://localhost:8081"
    //      (RabbitMQ management API requests, path "/api/...", use the management UI at "http://localhost:15672")
    //  And neither file contains a static JWT (no string matching "eyJ[A-Za-z0-9_-]+\.") — tokens are only fetched
    @Test
    @DisplayName("should use environment variables for hosts and no hard-coded tokens when the collection is read")
    void postman_usesVariablesAndNoStaticTokens() {
        Map<String, String> environment = postmanEnvironment();

        for (PostmanRequest request : postmanRequests()) {
            Matcher host = HOST_VARIABLE.matcher(request.host());
            assertThat(host.matches()).as("host of \"%s\" is a {{variable}}: %s", request.name(), request.host())
                    .isTrue();
            String expectedUrl = expectedBaseUrl(request);
            assertThat(environment.get(host.group(1))).as("environment value of {{%s}}", host.group(1))
                    .isEqualTo(expectedUrl);
        }
        for (Path file : List.of(POSTMAN_COLLECTION, POSTMAN_ENVIRONMENT)) {
            assertThat(STATIC_JWT.matcher(readText(file)).find()).as("%s contains a static JWT", file).isFalse();
        }
    }

    // AC-3 (ticket 08)
    // Given the collection
    // When each non-token request is reduced to "METHOD path" with path variables normalized
    //      ({{customerId}}, :id or a literal number in an id position -> {id})
    // Then all 7 operations are present at least once: POST /customers, GET /customers, GET /customers/search,
    //      GET /customers/{id}, GET /customers/{id}/score, PUT /customers/{id}, DELETE /customers/{id}
    //  And the assertion message lists any missing operation
    @Test
    @DisplayName("should exercise every API endpoint when the collection is read")
    void postman_coversEveryEndpoint() {
        Set<String> operations = postmanRequests().stream()
                .filter(request -> !request.isTokenRequest())
                .map(PostmanRequest::operation)
                .collect(Collectors.toSet());

        List<String> missing = CUSTOMER_OPERATIONS.stream()
                .filter(operation -> !operations.contains(operation))
                .toList();

        assertThat(missing).as("missing operations: %s", missing).isEmpty();
    }

    // AC-3 (ticket 08)
    // Given the collection
    // When each request's "test" event script is searched for a status assertion
    //      (pm.response.to.have.status(N) or pm.response.code ... N)
    // Then there is at least one request asserting each of 401, 403, 404, 409 and 422
    //      (e.g. 401: GET /customers without Authorization; 403: POST /customers with the USER token;
    //       404: GET /customers/999999; 409: duplicate CPF or stale version; 422: PUT changing the CPF)
    //  And the 401 request sends no Authorization header (auth type "noauth" or no header)
    @Test
    @DisplayName("should cover the 401, 403, 404, 409 and 422 error scenarios when the collection is read")
    void postman_coversErrorScenarios() {
        List<PostmanRequest> requests = postmanRequests();
        Set<Integer> asserted = requests.stream()
                .flatMap(request -> request.assertedStatuses().stream())
                .collect(Collectors.toSet());

        Set<Integer> missing = new TreeSet<>(ERROR_SCENARIO_STATUSES);
        missing.removeAll(asserted);
        assertThat(missing).as("error statuses no request asserts: %s", missing).isEmpty();
        assertThat(requests.stream().filter(request -> request.assertedStatuses().contains(401)))
                .allSatisfy(request -> assertThat(request.sendsCredentials())
                        .as("\"%s\" sends an Authorization header", request.name()).isFalse());
    }

    // AC-3 (ticket 08)
    // Given the collection and the WireMock score scenarios
    // When the requests to GET /customers/{id}/score are inspected
    // Then there are score requests asserting each of 200, 404, 503 and 504
    //  And the collection creates customers with the matching WireMock CPFs (52998224725 -> 200,
    //      11144477735 -> 404, 39053344705 -> 503, 93541134780 -> 504), i.e. each CPF appears in a POST /customers
    //      request body
    @Test
    @DisplayName("should cover the score scenarios when the collection is read")
    void postman_coversScoreScenarios() {
        List<PostmanRequest> requests = postmanRequests();
        Set<Integer> scoreStatuses = requests.stream()
                .filter(request -> request.operation().equals(SCORE_OPERATION))
                .flatMap(request -> request.assertedStatuses().stream())
                .collect(Collectors.toSet());
        String createBodies = requests.stream()
                .filter(request -> request.operation().equals(CREATE_OPERATION))
                .map(PostmanRequest::rawBody)
                .collect(Collectors.joining("\n"));

        assertThat(scoreStatuses).containsAll(SCORE_SCENARIO_STATUSES);
        assertThat(createBodies).as("POST /customers bodies").contains(SCORE_STATUS_BY_CPF.keySet());
    }

    // AC-3 (ticket 08)
    // Given the collection
    // When the RabbitMQ management API requests are inspected
    // Then a POST /api/queues/%2F/customer.created/get reads without consuming (ackmode "ack_requeue_true") and its
    //      test script looks for "CUSTOMER_CREATED"
    //  And at least two POST /api/exchanges/%2F/customer.events/publish requests (the event and its duplicate) send
    //      routing key "customer.status.change" with a "CUSTOMER_STATUS_CHANGE" payload carrying the same
    //      {{statusChangeEventId}}, and assert "routed"
    //  And every management API request uses its own basic auth with {{rabbitUser}} / {{rabbitPassword}}, which the
    //      environment sets to the docker-compose defaults guest / guest
    @Test
    @DisplayName("should cover the CUSTOMER_CREATED and CUSTOMER_STATUS_CHANGE scenarios when the collection is read")
    void postman_coversRabbitMqScenarios() {
        Map<String, String> environment = postmanEnvironment();
        List<PostmanRequest> managementRequests = postmanRequests().stream()
                .filter(PostmanRequest::isRabbitManagementRequest)
                .toList();
        List<PostmanRequest> createdReads = managementRequests.stream()
                .filter(request -> request.operation().equals(CREATED_QUEUE_GET_OPERATION))
                .toList();
        List<PostmanRequest> statusChangePublishes = managementRequests.stream()
                .filter(request -> request.operation().equals(EVENTS_EXCHANGE_PUBLISH_OPERATION))
                .toList();

        assertThat(createdReads).as(CREATED_QUEUE_GET_OPERATION).isNotEmpty().allSatisfy(request -> {
            assertThat(request.rawBody()).contains("\"ack_requeue_true\"");
            assertThat(request.testScript()).contains("CUSTOMER_CREATED");
        });
        assertThat(statusChangePublishes).as(EVENTS_EXCHANGE_PUBLISH_OPERATION).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(request -> {
                    assertThat(request.rawBody()).contains("\"customer.status.change\"", "CUSTOMER_STATUS_CHANGE",
                            "{{statusChangeEventId}}");
                    assertThat(request.testScript()).contains("routed");
                });
        assertThat(managementRequests).allSatisfy(request -> {
            assertThat(request.basicAuthValue("username")).as(request.name()).isEqualTo("{{rabbitUser}}");
            assertThat(request.basicAuthValue("password")).as(request.name()).isEqualTo("{{rabbitPassword}}");
        });
        assertThat(environment).containsEntry("rabbitUser", "guest").containsEntry("rabbitPassword", "guest");
    }

    private static String expectedBaseUrl(PostmanRequest request) {
        if (request.isTokenRequest()) {
            return KEYCLOAK_URL;
        }
        return request.isRabbitManagementRequest() ? RABBIT_MANAGEMENT_URL : APP_URL;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // OpenAPI (AC-2)
    // ---------------------------------------------------------------------------------------------------------------

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(TestcontainersConfiguration.class)
    @Testcontainers(disabledWithoutDocker = true)
    class RunningApplication {

        private static final String COMPONENT_SCHEMA_PREFIX = "#/components/schemas/";
        private static final String PROBLEM_JSON = "application/problem+json";
        private static final String APPLICATION_JSON = "application/json";
        private static final List<String> HTTP_METHODS = List.of("get", "put", "post", "delete", "patch", "head",
                "options", "trace");
        private static final List<String> CUSTOMER_FIELDS = List.of("id", "name", "cpf", "email", "status", "version",
                "createdAt", "updatedAt");
        private static final Map<String, Set<String>> ERROR_CODES_BY_OPERATION = errorCodesByOperation();

        @Autowired
        private MockMvc mockMvc;

        // AC-2 (ticket 08)
        // Given the running app
        // When GET /v3/api-docs is called with NO Authorization header
        // Then the response is 200 with a JSON body whose "openapi" field starts with "3."
        //  And "paths" contains these customer operations and no other /customers operation: post /customers, get /customers,
        //      get /customers/search, get /customers/{id}, get /customers/{id}/score, put /customers/{id},
        //      delete /customers/{id}
        //  And every operation has a non-blank "summary"
        @Test
        @DisplayName("should list every customer endpoint when the OpenAPI document is fetched without a token")
        void apiDocs_listsEveryEndpoint() throws Exception {
            JsonNode apiDocs = apiDocs();
            Map<String, JsonNode> operations = customerOperations(apiDocs);

            assertThat(apiDocs.path("openapi").asString()).startsWith("3.");
            assertThat(operations.keySet()).containsExactlyInAnyOrderElementsOf(ERROR_CODES_BY_OPERATION.keySet());
            operations.forEach((name, operation) ->
                    assertThat(operation.path("summary").asString()).as("summary of %s", name).isNotBlank());
        }

        // AC-2 (ticket 08)
        // Given GET /v3/api-docs (anonymous)
        // When "components.securitySchemes" is read
        // Then there is a scheme with type "http", scheme "bearer" and bearerFormat "JWT"
        //  And every /customers operation requires it (operation "security" or the global "security" names it)
        @Test
        @DisplayName("should declare a bearer JWT security scheme on every customer endpoint when the OpenAPI document is fetched")
        void apiDocs_declaresBearerJwtSecurityScheme() throws Exception {
            JsonNode apiDocs = apiDocs();

            Set<String> bearerSchemes = apiDocs.path("components").path("securitySchemes").properties().stream()
                    .filter(scheme -> scheme.getValue().path("type").asString().equals("http")
                            && scheme.getValue().path("scheme").asString().equals("bearer")
                            && scheme.getValue().path("bearerFormat").asString().equals("JWT"))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            assertThat(bearerSchemes).as("bearer JWT security schemes").isNotEmpty();
            customerOperations(apiDocs).forEach((name, operation) -> {
                JsonNode security = operation.has("security") ? operation.get("security") : apiDocs.path("security");
                Set<String> required = security.values().stream()
                        .flatMap(requirement -> requirement.propertyNames().stream())
                        .collect(Collectors.toSet());
                assertThat(required).as("security of %s", name).containsAnyElementsOf(bearerSchemes);
            });
        }

        // AC-2 (ticket 08)
        // Given GET /v3/api-docs (anonymous)
        // When the request bodies and success responses are read
        // Then post /customers and put /customers/{id} have a required "application/json" requestBody whose schema
        //      resolves (through $ref) to an object with properties: create -> name, cpf, email;
        //      update -> name, email, status, version
        //  And the success responses are: post 201, get/put 200, delete 204
        //  And the 200/201 customer responses resolve to a schema with id, name, cpf, email, status, version,
        //      createdAt, updatedAt; the score 200 resolves to customerId, cpf, score, classification
        @Test
        @DisplayName("should describe the request and response schemas when the OpenAPI document is fetched")
        void apiDocs_describesRequestAndResponseSchemas() throws Exception {
            JsonNode apiDocs = apiDocs();
            Map<String, JsonNode> operations = customerOperations(apiDocs);

            assertRequestBody(apiDocs, operations.get("post /customers"), "name", "cpf", "email");
            assertRequestBody(apiDocs, operations.get("put /customers/{id}"), "name", "email", "status", "version");
            operations.forEach((name, operation) -> assertThat(successCodes(operation)).as("success codes of %s", name)
                    .containsExactly(expectedSuccessCode(name)));
            assertThat(responseProperties(apiDocs, operations.get("post /customers"), "201"))
                    .containsAll(CUSTOMER_FIELDS);
            for (String name : List.of("get /customers/{id}", "put /customers/{id}")) {
                assertThat(responseProperties(apiDocs, operations.get(name), "200")).as(name)
                        .containsAll(CUSTOMER_FIELDS);
            }
            assertThat(responseProperties(apiDocs, operations.get("get /customers/{id}/score"), "200"))
                    .contains("customerId", "cpf", "score", "classification");
        }

        // AC-2 (ticket 08)
        // Given GET /v3/api-docs (anonymous)
        // When each operation's "responses" keys are read
        // Then each operation declares at least these error codes:
        //      post   /customers             -> 400, 401, 403, 409
        //      get    /customers             -> 400, 401
        //      get    /customers/search      -> 400, 401
        //      get    /customers/{id}        -> 401, 404
        //      get    /customers/{id}/score  -> 401, 404, 502, 503, 504
        //      put    /customers/{id}        -> 400, 401, 403, 404, 409, 422
        //      delete /customers/{id}        -> 401, 403, 404
        //  And every declared 4xx/5xx response has "application/problem+json" content
        //  And the assertion message lists each "operation: missing code"
        @Test
        @DisplayName("should declare the error responses of every endpoint when the OpenAPI document is fetched")
        void apiDocs_declaresErrorResponses() throws Exception {
            Map<String, JsonNode> operations = customerOperations(apiDocs());

            List<String> problems = new ArrayList<>();
            ERROR_CODES_BY_OPERATION.forEach((name, expectedCodes) -> {
                JsonNode responses = operations.get(name).path("responses");
                expectedCodes.stream().filter(code -> !responses.has(code))
                        .forEach(code -> problems.add(name + ": missing " + code));
                responses.properties().stream()
                        .filter(response -> isErrorCode(response.getKey()))
                        .filter(response -> !response.getValue().path("content").has(PROBLEM_JSON))
                        .forEach(response -> problems.add(name + ": " + response.getKey() + " is not " + PROBLEM_JSON));
            });

            assertThat(problems).as("error response problems: %s", problems).isEmpty();
        }

        private JsonNode apiDocs() throws Exception {
            String body = mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JSON.readTree(body);
        }

        private static Map<String, JsonNode> customerOperations(JsonNode apiDocs) {
            Map<String, JsonNode> operations = new LinkedHashMap<>();
            apiDocs.path("paths").properties().stream()
                    .filter(path -> path.getKey().startsWith("/customers"))
                    .forEach(path -> path.getValue().properties().stream()
                            .filter(operation -> HTTP_METHODS.contains(operation.getKey()))
                            .forEach(operation -> operations.put(operation.getKey() + " " + path.getKey(),
                                    operation.getValue())));
            return operations;
        }

        private static void assertRequestBody(JsonNode apiDocs, JsonNode operation, String... properties) {
            JsonNode requestBody = operation.path("requestBody");
            assertThat(requestBody.path("required").asBoolean()).as("request body is required").isTrue();
            JsonNode schema = resolve(apiDocs, requestBody.path("content").path(APPLICATION_JSON).path("schema"));
            assertThat(schema.path("properties").propertyNames()).contains(properties);
        }

        private static List<String> successCodes(JsonNode operation) {
            return operation.path("responses").propertyNames().stream().filter(code -> code.startsWith("2")).toList();
        }

        private static String expectedSuccessCode(String operationName) {
            if (operationName.startsWith("post ")) {
                return "201";
            }
            return operationName.startsWith("delete ") ? "204" : "200";
        }

        private static Collection<String> responseProperties(JsonNode apiDocs, JsonNode operation, String code) {
            JsonNode content = operation.path("responses").path(code).path("content");
            assertThat(content.values()).as("content of response %s", code).isNotEmpty();
            JsonNode schema = resolve(apiDocs, content.values().iterator().next().path("schema"));
            return schema.path("properties").propertyNames();
        }

        private static JsonNode resolve(JsonNode apiDocs, JsonNode schema) {
            JsonNode resolved = schema;
            while (resolved.has("$ref")) {
                String name = resolved.get("$ref").asString().substring(COMPONENT_SCHEMA_PREFIX.length());
                resolved = apiDocs.path("components").path("schemas").path(name);
            }
            return resolved;
        }

        private static boolean isErrorCode(String code) {
            return code.startsWith("4") || code.startsWith("5");
        }

        private static Map<String, Set<String>> errorCodesByOperation() {
            Map<String, Set<String>> codes = new LinkedHashMap<>();
            codes.put("post /customers", Set.of("400", "401", "403", "409"));
            codes.put("get /customers", Set.of("400", "401"));
            codes.put("get /customers/search", Set.of("400", "401"));
            codes.put("get /customers/{id}", Set.of("401", "404"));
            codes.put("get /customers/{id}/score", Set.of("401", "404", "502", "503", "504"));
            codes.put("put /customers/{id}", Set.of("400", "401", "403", "404", "409", "422"));
            codes.put("delete /customers/{id}", Set.of("401", "403"));
            return codes;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // README helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** README "## " sections keyed by normalized heading; a "# " heading ends a section. Code fences are skipped. */
    private static Map<String, List<String>> readmeSections() {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        boolean inCodeFence = false;
        for (String line : readLines(README)) {
            if (line.strip().startsWith("```")) {
                inCodeFence = !inCodeFence;
            }
            if (!inCodeFence && line.startsWith("## ")) {
                current = new ArrayList<>();
                sections.putIfAbsent(normalizedHeading(line.substring("## ".length())), current);
            } else if (!inCodeFence && line.startsWith("# ")) {
                current = null;
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }

    private static String sectionText(String heading) {
        List<String> section = readmeSections().get(normalizedHeading(heading));
        assertThat(section).as("README section \"%s\"", heading).isNotNull();
        return String.join("\n", section);
    }

    private static String normalizedHeading(String heading) {
        return heading.strip().toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> applicationYamlDefaults() {
        Map<String, String> defaults = new LinkedHashMap<>();
        Matcher placeholder = PLACEHOLDER.matcher(readText(APPLICATION_YAML));
        while (placeholder.find()) {
            defaults.putIfAbsent(placeholder.group(1), placeholder.group(2));
        }
        return defaults;
    }

    private record ConfigurationRow(String line, List<String> names, List<String> defaults) {

        private static final int NAMES_COLUMN = 1;
        private static final int DEFAULTS_COLUMN = 3;

        static ConfigurationRow parse(String line) {
            String[] columns = line.split("\\|");
            return new ConfigurationRow(line, backticked(columns[NAMES_COLUMN]), backticked(columns[DEFAULTS_COLUMN]));
        }

        private static List<String> backticked(String column) {
            return BACKTICKED.matcher(column).results().map(match -> match.group(1)).toList();
        }
    }

    /** Data rows of the configuration table: table lines whose first column holds a backticked variable name. */
    private static List<ConfigurationRow> readmeConfigurationRows() {
        return sectionText(CONFIGURATION_SECTION).lines()
                .filter(line -> line.startsWith("|") && line.split("\\|").length > ConfigurationRow.DEFAULTS_COLUMN)
                .map(ConfigurationRow::parse)
                .filter(row -> !row.names().isEmpty())
                .toList();
    }

    private static Map<String, String> readmeConfigurationDefaults() {
        Map<String, String> defaults = new HashMap<>();
        for (ConfigurationRow row : readmeConfigurationRows()) {
            if (row.names().size() == row.defaults().size()) {
                for (int index = 0; index < row.names().size(); index++) {
                    defaults.put(row.names().get(index), row.defaults().get(index));
                }
            }
        }
        return defaults;
    }

    private static Set<String> wireMockScenarioCpfs() {
        try (Stream<Path> mappings = Files.list(WIREMOCK_MAPPINGS)) {
            return mappings.filter(file -> file.toString().endsWith(".json"))
                    .map(file -> SCORE_URL.matcher(readJson(file).path("request").path("url").asString()))
                    .filter(Matcher::matches)
                    .map(url -> url.group(1))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Postman helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** A leaf request of the collection, with the auth type it inherits from its folders when it has none. */
    private record PostmanRequest(String name, JsonNode request, String inheritedAuthType, String testScript) {

        static PostmanRequest of(JsonNode item, String inheritedAuthType) {
            String script = item.path("event").values().stream()
                    .filter(event -> event.path("listen").asString().equals("test"))
                    .map(event -> scriptText(event.path("script").path("exec")))
                    .collect(Collectors.joining("\n"));
            return new PostmanRequest(item.path("name").asString(), item.path("request"), inheritedAuthType, script);
        }

        private static String scriptText(JsonNode exec) {
            return exec.isArray()
                    ? exec.values().stream().map(JsonNode::asString).collect(Collectors.joining("\n"))
                    : exec.asString();
        }

        String rawUrl() {
            JsonNode url = request.path("url");
            return url.isString() ? url.asString() : url.path("raw").asString();
        }

        String host() {
            String raw = rawUrl();
            int pathStart = raw.indexOf('/');
            return pathStart < 0 ? raw : raw.substring(0, pathStart);
        }

        /** "METHOD path" with the query dropped and id segments ({{var}}, :var, digits) normalized to {id}. */
        String operation() {
            String path = rawUrl().substring(host().length()).split("\\?", 2)[0];
            String normalizedPath = Arrays.stream(path.split("/"))
                    .map(segment -> ID_SEGMENT.matcher(segment).matches() ? "{id}" : segment)
                    .collect(Collectors.joining("/"));
            return request.path("method").asString() + " " + normalizedPath;
        }

        boolean isTokenRequest() {
            return request.path("method").asString().equals("POST") && rawUrl().contains(TOKEN_PATH);
        }

        boolean isRabbitManagementRequest() {
            return rawUrl().substring(host().length()).startsWith(RABBIT_MANAGEMENT_PATH);
        }

        /** A request-level basic auth field; inherited auth does not count. */
        String basicAuthValue(String key) {
            JsonNode auth = request.path("auth");
            if (!auth.path("type").asString().equals("basic")) {
                return "";
            }
            return auth.path("basic").values().stream()
                    .filter(field -> field.path("key").asString().equals(key))
                    .map(field -> field.path("value").asString())
                    .findFirst()
                    .orElse("");
        }

        String formValue(String key) {
            return request.path("body").path("urlencoded").values().stream()
                    .filter(field -> field.path("key").asString().equals(key))
                    .map(field -> field.path("value").asString())
                    .findFirst()
                    .orElse("");
        }

        String rawBody() {
            return request.path("body").path("raw").asString();
        }

        Set<Integer> assertedStatuses() {
            return ASSERTED_STATUS.matcher(testScript).results()
                    .map(match -> Integer.parseInt(match.group(1) != null ? match.group(1) : match.group(2)))
                    .collect(Collectors.toSet());
        }

        boolean sendsCredentials() {
            String authType = request.has("auth") ? request.path("auth").path("type").asString() : inheritedAuthType;
            boolean authenticated = authType != null && !authType.isEmpty() && !authType.equals("noauth");
            boolean authorizationHeader = request.path("header").values().stream()
                    .anyMatch(header -> header.path("key").asString().equalsIgnoreCase("Authorization"));
            return authenticated || authorizationHeader;
        }
    }

    private static List<PostmanRequest> postmanRequests() {
        JsonNode collection = readJson(POSTMAN_COLLECTION);
        List<PostmanRequest> requests = new ArrayList<>();
        collectRequests(collection.path("item"), authTypeOf(collection, null), requests);
        assertThat(requests).as("requests in the collection").isNotEmpty();
        return requests;
    }

    private static void collectRequests(JsonNode items, String inheritedAuthType, List<PostmanRequest> requests) {
        for (JsonNode item : items.values()) {
            String authType = authTypeOf(item, inheritedAuthType);
            if (item.has("item")) {
                collectRequests(item.get("item"), authType, requests);
            } else {
                requests.add(PostmanRequest.of(item, authType));
            }
        }
    }

    private static String authTypeOf(JsonNode node, String inheritedAuthType) {
        return node.has("auth") ? node.path("auth").path("type").asString() : inheritedAuthType;
    }

    private static Map<String, String> postmanEnvironment() {
        Map<String, String> values = new HashMap<>();
        readJson(POSTMAN_ENVIRONMENT).path("values").values()
                .forEach(entry -> values.put(entry.path("key").asString(), entry.path("value").asString()));
        return values;
    }

    /** Replaces each {{variable}} with its environment value, leaving unknown variables untouched. */
    private static String resolve(String value, Map<String, String> environment) {
        return VARIABLE.matcher(value).replaceAll(match ->
                Matcher.quoteReplacement(environment.getOrDefault(match.group(1), match.group())));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // File helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static List<String> readLines(Path file) {
        return readText(file).lines().toList();
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonNode readJson(Path file) {
        return JSON.readTree(readText(file));
    }
}
