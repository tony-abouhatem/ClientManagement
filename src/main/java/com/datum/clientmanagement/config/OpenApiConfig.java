package com.datum.clientmanagement.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;

import java.util.Map;
import java.util.Objects;

/**
 * OpenAPI document served at {@code /v3/api-docs} and rendered by the Swagger UI (ticket 08). Every operation
 * requires a Keycloak access token, and every error response is documented as RFC 7807 problem+json (NFR-13).
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "ClientManagement API", version = "v1",
                description = "Customer CRUD, search and external score lookup."),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_JWT))
@SecurityScheme(name = OpenApiConfig.BEARER_JWT, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "Access token issued by Keycloak (realm client-management).")
public class OpenApiConfig {

    static final String BEARER_JWT = "bearer-jwt";

    private static final String PROBLEM_DETAIL_SCHEMA = "#/components/schemas/" + ProblemDetail.class.getSimpleName();
    private static final String CLIENT_ERROR_PREFIX = "4";
    private static final String SERVER_ERROR_PREFIX = "5";

    /**
     * The controller annotations only declare which error codes an operation returns; their body is always a
     * ProblemDetail, so it is set here once instead of being repeated on every annotation.
     */
    @Bean
    OpenApiCustomizer problemDetailErrorResponses() {
        return openApi -> {
            registerProblemDetailSchema(openApi);
            Content problemContent = new Content().addMediaType(
                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    new MediaType().schema(new Schema<>().$ref(PROBLEM_DETAIL_SCHEMA)));
            openApi.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .flatMap(operation -> operation.getResponses().entrySet().stream())
                    .filter(response -> isErrorCode(response.getKey()))
                    .map(Map.Entry::getValue)
                    .forEach(response -> response.setContent(problemContent));
        };
    }

    @SuppressWarnings("rawtypes") // swagger-core's ModelConverters API returns raw Schema maps.
    private void registerProblemDetailSchema(OpenAPI openApi) {
        Map<String, Schema> schemas = ModelConverters.getInstance().read(ProblemDetail.class);
        Components components = Objects.requireNonNullElseGet(openApi.getComponents(), Components::new);
        schemas.forEach(components::addSchemas);
        openApi.setComponents(components);
    }

    private boolean isErrorCode(String code) {
        return code.startsWith(CLIENT_ERROR_PREFIX) || code.startsWith(SERVER_ERROR_PREFIX);
    }
}
