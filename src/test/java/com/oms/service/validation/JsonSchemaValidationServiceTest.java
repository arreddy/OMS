package com.oms.service.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaValidationServiceTest {

    private final JsonSchemaValidationService service = new JsonSchemaValidationService(new ObjectMapper());

    private static final String SCHEMA = """
            {"type":"object","properties":{"giftMessage":{"type":"string","maxLength":5}},"required":["giftMessage"]}
            """;

    @Test
    void passesWhenDataSatisfiesSchema() {
        assertThatCode(() -> service.validate(SCHEMA, "{\"giftMessage\":\"hi\"}")).doesNotThrowAnyException();
    }

    @Test
    void failsWhenRequiredFieldMissing() {
        assertThatThrownBy(() -> service.validate(SCHEMA, "{}"))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    void failsWhenFieldExceedsMaxLength() {
        assertThatThrownBy(() -> service.validate(SCHEMA, "{\"giftMessage\":\"too long for schema\"}"))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    void failsOnMalformedDataJson() {
        assertThatThrownBy(() -> service.validate(SCHEMA, "not json"))
                .isInstanceOf(SchemaValidationException.class);
    }
}
