package com.rekordo.configuration;

import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Marks every property the server may answer with null as nullable in the published schema.
 *
 * <p>Without this the contract lies to the clients, and it lies in the one direction static
 * typing cannot recover from. springdoc has exactly one way of saying "this field might not
 * be there": leaving it out of {@code required}. Orval reads that as {@code field?: T}, i.e.
 * {@code T | undefined} -- while Jackson serialises the field as an explicit {@code null},
 * on purpose (see {@code SharedCopyDto}: fields a viewer may not see are null rather than
 * absent, so one shape serves the friend view and the public page). A generated
 * {@code !== undefined} guard then narrows a live null to a non-null type and the next line
 * dereferences it. That is not hypothetical: it took the shared record sheet down twice.
 *
 * <p>So: not-required means nullable, stated rather than implied, and the clients generate
 * {@code T | null | undefined}. Anything carrying {@code @NotNull} and friends is already in
 * {@code required} and is left alone, which is what keeps this from weakening the fields
 * that really are guaranteed.
 *
 * <p>Applied to request schemas too, and correctly so: the server reads an omitted field and
 * an explicit null identically, so a client is entitled to send either.
 */
@Configuration
public class NullableSchemaCustomizer {

    @Bean
    public OpenApiCustomizer nullableOptionalProperties() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().values().forEach(NullableSchemaCustomizer::apply);
        };
    }

    private static void apply(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return;
        }
        Set<String> required = schema.getRequired() == null ? Set.of() : Set.copyOf(schema.getRequired());
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            Schema<?> property = entry.getValue();
            // Nested inline objects (a record inside a record that springdoc did not lift into
            // its own component) carry their own properties and their own required list.
            apply(property);
            if (required.contains(entry.getKey())) {
                continue;
            }
            if (property.get$ref() != null) {
                // A $ref carries no type of its own to widen, so the union has to be spelt out.
                // `anyOf` rather than `oneOf`: the two branches are not mutually exclusive to a
                // validator, and every generator reads anyOf as the plain union it is.
                entry.setValue(schema31()
                        .anyOf(List.of(schema31().$ref(property.get$ref()), nullBranch())));
            } else if (property.getTypes() != null && !property.getTypes().isEmpty()) {
                property.addType("null");
            } else if (property.getType() != null) {
                // A schema built through 3.0's single `type` field rather than 3.1's set. Its
                // own type has to be carried over first, or widening it would erase it.
                property.addType(property.getType());
                property.addType("null");
            }
        }
    }

    /** OpenAPI 3.1 spells null as a type, not as 3.0's `nullable` flag -- which it ignores. */
    private static Schema<?> nullBranch() {
        Schema<?> nullSchema = schema31();
        nullSchema.addType("null");
        return nullSchema;
    }

    /**
     * A schema that serialises under 3.1's rules like the ones springdoc built.
     *
     * Without the explicit version a hand-made schema defaults to 3.0, where the serialiser
     * reads the single `type` field and ignores `types` -- so the null branch came out as an
     * empty `{}`, which every generator reads as "anything".
     */
    private static Schema<?> schema31() {
        return new Schema<>().specVersion(SpecVersion.V31);
    }
}
