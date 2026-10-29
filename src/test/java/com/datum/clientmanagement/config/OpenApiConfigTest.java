package com.datum.clientmanagement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/ProblemDetail";

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void problemDetailErrorResponses_setsProblemJsonOnClientAndServerErrors() {
        ApiResponses responses = responses("400", "404", "503");

        customize(responses);

        for (String code : responses.keySet()) {
            MediaType problem = responses.get(code).getContent().get(PROBLEM_JSON);
            assertThat(problem).as("content of %s", code).isNotNull();
            assertThat(problem.getSchema().get$ref()).isEqualTo(PROBLEM_DETAIL_REF);
            assertThat(responses.get(code).getContent()).hasSize(1);
        }
    }

    @Test
    void problemDetailErrorResponses_leavesSuccessResponsesUntouched() {
        Content customerContent = new Content().addMediaType("*/*", new MediaType().schema(new Schema<>()));
        ApiResponses responses = responses("201", "204");
        responses.get("201").setContent(customerContent);

        customize(responses);

        assertThat(responses.get("201").getContent()).isSameAs(customerContent);
        assertThat(responses.get("204").getContent()).isNull();
    }

    @Test
    void problemDetailErrorResponses_registersTheProblemDetailSchema() {
        OpenAPI openApi = openApiWith(responses("404"));

        config.problemDetailErrorResponses().customise(openApi);

        assertThat(openApi.getComponents().getSchemas().get("ProblemDetail").getProperties())
                .containsKeys("type", "title", "status", "detail", "instance");
    }

    private void customize(ApiResponses responses) {
        config.problemDetailErrorResponses().customise(openApiWith(responses));
    }

    private static ApiResponses responses(String... codes) {
        ApiResponses responses = new ApiResponses();
        for (String code : codes) {
            responses.addApiResponse(code, new ApiResponse().description("response " + code));
        }
        return responses;
    }

    private static OpenAPI openApiWith(ApiResponses responses) {
        PathItem path = new PathItem().get(new Operation().responses(responses));
        return new OpenAPI().paths(new Paths().addPathItem("/customers", path));
    }
}
