package com.oms.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.oms.domain.workflow.BadgeCategory;

import java.util.UUID;

public final class OrderTypeDtos {

    private OrderTypeDtos() {
    }

    public record CreateOrderTypeRequest(String code, String name, JsonNode attributeSchema, JsonNode lineAttributeSchema) {
    }

    /** Either field may be omitted to leave that schema untouched. */
    public record UpdateOrderTypeSchemaRequest(JsonNode attributeSchema, JsonNode lineAttributeSchema) {
    }

    public record OrderTypeResponse(UUID orderTypeId, String code, String name, JsonNode attributeSchema,
                                     JsonNode lineAttributeSchema, UUID workflowDefinitionId, boolean active) {
    }

    public record ActiveWorkflowSummary(UUID workflowDefinitionId, int version, String name) {
    }

    public record OrderTypeSchemaResponse(String code, JsonNode attributeSchema, JsonNode lineAttributeSchema,
                                           ActiveWorkflowSummary activeWorkflow) {
    }

    /** One distinct status code in use across active order types' workflows, with its badge category (UI spec §2.1, §6). */
    public record StatusTaxonomyEntry(String code, BadgeCategory badgeCategory) {
    }
}
