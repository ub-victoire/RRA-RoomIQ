package rw.rra.roomiq.audit.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import rw.rra.roomiq.audit.api.AuditEventSearchItemResponse;
import rw.rra.roomiq.audit.api.AuditEventSearchPageResponse;
import rw.rra.roomiq.common.web.ApiError;

import java.util.Map;

@Configuration
public class AuditOpenApiConfiguration {
    private static final String PATH = "/api/v1/audit-events";
    private static final String CORRELATION_ID = "X-Correlation-ID";
    private static final String API_ERROR_SCHEMA = "#/components/schemas/ApiError";
    private static final Map<String, String> ERROR_RESPONSES = Map.of(
            "400", "Invalid query parameter: INVALID_REQUEST or INVALID_SORT_FIELD.",
            "401", "Authentication is required or invalid: AUTHENTICATION_REQUIRED.",
            "403", "Identity denied access or the requested building is outside trusted scope: ACCESS_DENIED.",
            "503", "Identity authorization is unavailable or unusable: IDENTITY_AUTHORIZATION_UNAVAILABLE.");

    @Bean
    OpenApiCustomizer auditApiContractCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSecuritySchemes("bearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Identity Service bearer access token."));
            addModelSchemas(openApi.getComponents(), ApiError.class,
                    AuditEventSearchItemResponse.class, AuditEventSearchPageResponse.class);
            openApi.getComponents().addSchemas("AuditEventSearchApiResponse", apiResponseSchema());

            if (openApi.getPaths() == null || openApi.getPaths().get(PATH) == null
                    || openApi.getPaths().get(PATH).getGet() == null) {
                return;
            }
            Operation operation = openApi.getPaths().get(PATH).getGet();
            operation.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
            if (operation.getParameters() == null || operation.getParameters().stream()
                    .noneMatch(parameter -> CORRELATION_ID.equalsIgnoreCase(parameter.getName()))) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name(CORRELATION_ID)
                        .description("Optional correlation ID; the service preserves or replaces invalid values.")
                        .required(false)
                        .schema(new Schema<String>().type("string")));
            }
            if (operation.getResponses() == null) {
                operation.setResponses(new ApiResponses());
            }
            operation.getResponses().computeIfAbsent("200",
                    status -> new ApiResponse().description("Audit events retrieved"))
                    .content(new Content().addMediaType("application/json",
                            new MediaType().schema(new Schema<>().$ref(
                                    "#/components/schemas/AuditEventSearchApiResponse"))));
            ERROR_RESPONSES.forEach((status, description) -> {
                ApiResponse errorResponse = new ApiResponse().description(description)
                        .content(new Content().addMediaType("application/json",
                                new MediaType().schema(new Schema<>().$ref(API_ERROR_SCHEMA))));
                operation.getResponses().putIfAbsent(status, errorResponse);
            });
            operation.getResponses().values().forEach(response -> response.addHeaderObject(CORRELATION_ID,
                    new Header().description("Correlation ID echoed by the service.")
                            .schema(new Schema<String>().type("string"))));
        };
    }

    private static void addModelSchemas(Components components, Class<?>... types) {
        for (Class<?> type : types) {
            ModelConverters.getInstance().read(type).forEach(components::addSchemas);
        }
    }

    private static Schema<Object> apiResponseSchema() {
        Schema<Object> schema = new Schema<>();
        schema.type("object");
        schema.addProperty("success", new Schema<Boolean>().type("boolean"));
        schema.addProperty("message", new Schema<String>().type("string"));
        schema.addProperty("data", new Schema<>().$ref("#/components/schemas/AuditEventSearchPage"));
        schema.addProperty("metadata", new Schema<Object>().type("object")
                .additionalProperties(new Schema<>()));
        schema.addProperty("timestamp", new Schema<String>().type("string").format("date-time"));
        return schema;
    }
}
