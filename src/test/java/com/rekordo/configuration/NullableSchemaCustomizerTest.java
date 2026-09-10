package com.rekordo.configuration;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one promise the published schema makes to the generated clients.
 *
 * <p>What is asserted here is not a formatting detail: a property the server may answer with
 * null but the schema calls a plain string becomes {@code field?: string} in TypeScript, a
 * {@code !== undefined} guard narrows the live null to {@code string}, and the next line
 * dereferences it. That crash shipped twice before this customizer existed.
 */
class NullableSchemaCustomizerTest {

    private static OpenAPI customized(OpenAPI openApi) {
        new NullableSchemaCustomizer().nullableOptionalProperties().customise(openApi);
        return openApi;
    }

    private static Schema<?> objectSchema() {
        return new Schema<>().specVersion(SpecVersion.V31).type("object");
    }

    private static Schema<?> stringSchema() {
        Schema<?> schema = new Schema<>().specVersion(SpecVersion.V31);
        schema.addType("string");
        return schema;
    }

    private static OpenAPI withSchema(String name, Schema<?> schema) {
        return new OpenAPI()
                .specVersion(SpecVersion.V31)
                .components(new Components().addSchemas(name, schema));
    }

    @Test
    @DisplayName("a property outside `required` becomes nullable")
    void widensOptionalProperties() {
        Schema<?> dto = objectSchema().addProperty("releaseId", stringSchema());

        customized(withSchema("Dto", dto));

        assertThat(dto.getProperties().get("releaseId").getTypes()).containsExactly("string", "null");
    }

    @Test
    @DisplayName("a required property keeps its guarantee")
    void leavesRequiredPropertiesAlone() {
        Schema<?> dto = objectSchema().addProperty("email", stringSchema());
        dto.setRequired(List.of("email"));

        customized(withSchema("Dto", dto));

        assertThat(dto.getProperties().get("email").getTypes()).containsExactly("string");
    }

    @Test
    @DisplayName("a $ref property becomes a union with null, because a ref has no type to widen")
    void wrapsReferencesInAnyOf() {
        Schema<?> dto =
                objectSchema().addProperty("coverTheme", new Schema<>().$ref("#/components/schemas/CoverThemeDto"));

        customized(withSchema("Dto", dto));

        Schema<?> property = dto.getProperties().get("coverTheme");
        assertThat(property.get$ref()).isNull();
        assertThat(property.getAnyOf()).hasSize(2);
        assertThat(property.getAnyOf().get(0).get$ref()).isEqualTo("#/components/schemas/CoverThemeDto");
        assertThat(property.getAnyOf().get(1).getTypes()).containsExactly("null");
    }

    /**
     * The trap that made the first attempt useless: a hand-made schema defaults to the 3.0
     * serialiser, which reads the single `type` field and ignores `types` -- so the null
     * branch left the server as an empty `{}`, which a generator reads as "anything at all".
     * Only the serialised form can catch that, so it is asserted on the JSON and not on the
     * model.
     */
    @Test
    @DisplayName("the null branch survives serialisation as a type, not as an empty schema")
    void serialisesTheNullBranch() {
        Schema<?> dto =
                objectSchema().addProperty("coverTheme", new Schema<>().$ref("#/components/schemas/CoverThemeDto"));

        String json = Json31.pretty(customized(withSchema("Dto", dto)));

        assertThat(json).contains("\"type\" : \"null\"");
    }

    @Test
    @DisplayName("an inline nested object is widened too, on its own required list")
    void descendsIntoInlineObjects() {
        Schema<?> nested = objectSchema().addProperty("label", stringSchema());
        nested.setRequired(List.of("label"));
        Schema<?> dto = objectSchema().addProperty("inner", nested).addProperty("note", stringSchema());

        customized(withSchema("Dto", dto));

        assertThat(nested.getProperties().get("label").getTypes()).containsExactly("string");
        assertThat(dto.getProperties().get("note").getTypes()).containsExactly("string", "null");
        // The nested object itself is a property of Dto and not required, so it is nullable.
        assertThat(dto.getProperties().get("inner").getTypes()).contains("null");
    }

    @Test
    @DisplayName("a document with no components at all is left alone rather than failing")
    void toleratesAnEmptyDocument() {
        OpenAPI empty = new OpenAPI().specVersion(SpecVersion.V31);

        customized(empty);

        assertThat(empty.getComponents()).isNull();
    }
}
