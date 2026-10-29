/*
 * NFR-10 (docs/PRODUCT.md); tickets 01 and 06 (GitHub issues #2, #7).
 *
 * Interfaces under test: app startup, /actuator/health, customer CRUD endpoints.
 * Testcontainers Postgres up; RabbitMQ and/or Score (WireMock) deliberately absent (properties point at closed ports).
 */
package com.datum.clientmanagement.nonfunctional;

import com.datum.clientmanagement.TestPorts;
import com.datum.clientmanagement.TestcontainersConfiguration;
import com.datum.clientmanagement.customer.ScoreClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Each dependency outage needs its own application context, hence one nested class per outage.
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"}) // IDE has no database; the SQL runs against Testcontainers Postgres
class AvailabilityNfrTest {

    private static final String ANA_BODY =
            "{\"name\":\"Ana Souza\",\"cpf\":\"52998224725\",\"email\":\"ana@example.com\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import(RabbitMqDownConfiguration.class)
    class RabbitMqDown {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private ApplicationContext applicationContext;

        @BeforeEach
        void deleteAllCustomers() {
            jdbcTemplate.update("DELETE FROM customers");
        }

        // NFR-10:availability
        // Given Postgres is up and spring.rabbitmq.port points at a closed port (RabbitMQ down at startup)
        // When the application context starts
        // Then startup succeeds (no exception, context active)
        //  And GET /actuator/health returns 503 with "status":"DOWN" and components.rabbit.status == "DOWN"
        //      while components.db.status == "UP"
        @Test
        @DisplayName("should start and report rabbit DOWN in health when RabbitMQ is unavailable at startup")
        void startsWithoutRabbit_healthShowsRabbitDown() throws Exception {
            assertThat(((ConfigurableApplicationContext) applicationContext).isActive()).isTrue();

            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"))
                    .andExpect(jsonPath("$.components.rabbit.status").value("DOWN"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"));
        }

        // NFR-10:availability
        // Given the app started with RabbitMQ down
        // When an ADMIN POSTs /customers {"name":"Ana Souza","cpf":"52998224725","email":"ana@example.com"},
        //      then GET, PUT and DELETE on that customer
        // Then the responses are 201, 200, 200 and 204 respectively
        @Test
        @DisplayName("should keep CRUD working when RabbitMQ is down")
        void crudWorksWithoutRabbit() throws Exception {
            long id = createCustomer(mockMvc);
            String path = "/customers/" + id;

            asAdmin(mockMvc, get(path)).andExpect(status().isOk());
            asAdmin(mockMvc, put(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Ana Souza\",\"email\":\"ana@example.com\",\"status\":\"INACTIVE\",\"version\":0}"))
                    .andExpect(status().isOk());
            asAdmin(mockMvc, delete(path)).andExpect(status().isNoContent());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @Import({TestcontainersConfiguration.class, ScoreDownConfiguration.class})
    class ScoreServiceDown {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private CircuitBreakerRegistry circuitBreakerRegistry;

        @Value("${resilience4j.circuitbreaker.instances.score.minimum-number-of-calls}")
        private int callsToOpenCircuit;

        @BeforeEach
        void resetState() {
            jdbcTemplate.update("DELETE FROM customers");
            scoreCircuitBreaker().reset();
        }

        // NFR-10:availability
        // Given Postgres and RabbitMQ are up and SCORE_BASE_URL points at a closed port (WireMock down)
        // When the application context starts
        // Then startup succeeds, POST/GET /customers work (201/200)
        //  And GET /customers/{id}/score returns 503 problem+json
        @Test
        @DisplayName("should start and keep CRUD working when the Score service is down")
        void startsWithoutScoreService() throws Exception {
            long id = createCustomer(mockMvc);

            asAdmin(mockMvc, get("/customers/" + id)).andExpect(status().isOk());
            asAdmin(mockMvc, get("/customers/" + id + "/score"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(503));
        }

        // NFR-10:availability (Score is optional)
        // Given Postgres and RabbitMQ are up and the Score service is down
        // When enough GET /customers/{id}/score calls fail to open the "score" circuit breaker
        // Then GET /actuator/health returns 200 with "status":"UP" (db and rabbit UP)
        //  And components.circuitBreakers shows the "score" breaker as CIRCUIT_OPEN (state OPEN)
        @Test
        @DisplayName("should keep overall health UP and show the Score circuit breaker as open when Score is down")
        void openScoreCircuit_keepsHealthUp() throws Exception {
            long id = createCustomer(mockMvc);

            for (int call = 0; call < callsToOpenCircuit; call++) {
                asAdmin(mockMvc, get("/customers/" + id + "/score")).andExpect(status().isServiceUnavailable());
            }

            assertThat(scoreCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components.db.status").value("UP"))
                    .andExpect(jsonPath("$.components.rabbit.status").value("UP"))
                    .andExpect(jsonPath("$.components.circuitBreakers.details.score.status").value("CIRCUIT_OPEN"))
                    .andExpect(jsonPath("$.components.circuitBreakers.details.score.details.state").value("OPEN"));
        }

        private CircuitBreaker scoreCircuitBreaker() {
            return circuitBreakerRegistry.circuitBreaker(ScoreClient.RESILIENCE_INSTANCE);
        }
    }

    /** PostgreSQL only; RabbitMQ is pointed at a port nothing listens on. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RabbitMqDownConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);
        }

        @Bean
        DynamicPropertyRegistrar rabbitOnClosedPort() {
            int closedPort = TestPorts.freePort();
            return registry -> {
                registry.add("spring.rabbitmq.host", () -> "localhost");
                registry.add("spring.rabbitmq.port", () -> closedPort);
            };
        }
    }

    /** Score is pointed at a port nothing listens on. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScoreDownConfiguration {

        @Bean
        DynamicPropertyRegistrar scoreOnClosedPort() {
            int closedPort = TestPorts.freePort();
            return registry -> registry.add("app.score.base-url", () -> "http://localhost:" + closedPort);
        }
    }

    private static long createCustomer(MockMvc mockMvc) throws Exception {
        String body = asAdmin(mockMvc, post("/customers").contentType(MediaType.APPLICATION_JSON).content(ANA_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).path("id").asLong();
    }

    private static ResultActions asAdmin(MockMvc mockMvc, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
}
