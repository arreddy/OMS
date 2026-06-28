package com.oms.service.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Validates order.attributes / order_line.attributes against the JSON Schema
 * stored in order_type.attribute_schema / line_attribute_schema (SPEC.md §3.3).
 */
@Service
public class JsonSchemaValidationService {

    private final ObjectMapper objectMapper;
    private final SchemaRegistry schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    public JsonSchemaValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @throws SchemaValidationException if dataJson doesn't satisfy schemaJson */
    public void validate(String schemaJson, String dataJson) {
        JsonNode schemaNode = readJson(schemaJson, "schema");
        JsonNode dataNode = readJson(dataJson == null || dataJson.isBlank() ? "{}" : dataJson, "data");

        Schema schema = schemaRegistry.getSchema(schemaNode);
        List<Error> errors = schema.validate(dataNode);
        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors.stream().map(Error::getMessage).toList());
        }
    }

    private JsonNode readJson(String json, String label) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SchemaValidationException(List.of("Invalid " + label + " JSON: " + e.getOriginalMessage()));
        }
    }
}
