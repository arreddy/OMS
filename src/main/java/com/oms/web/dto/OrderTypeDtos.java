package com.oms.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

public final class OrderTypeDtos {

    private OrderTypeDtos() {
    }

    public record CreateOrderTypeRequest(String code, String name, JsonNode attributeSchema, JsonNode lineAttributeSchema) {
    }

    public record OrderTypeResponse(UUID orderTypeId, String code, String name, JsonNode attributeSchema,
                                     JsonNode lineAttributeSchema, UUID workflowDefinitionId, boolean active) {
    }

    public record ActiveWorkflowSummary(UUID workflowDefinitionId, int version, String name) {
    }

    public record OrderTypeSchemaResponse(String code, JsonNode attributeSchema, JsonNode lineAttributeSchema,
                                           ActiveWorkflowSummary activeWorkflow) {
    }
}
