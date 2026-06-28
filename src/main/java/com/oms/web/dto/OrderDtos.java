package com.oms.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateLineRequest(String itemRef, BigDecimal quantity, BigDecimal unitPrice, JsonNode attributes) {
    }

    public record CreateOrderRequest(String orderTypeCode, String customerRef, String currency,
                                      BigDecimal totalAmount, JsonNode attributes, List<CreateLineRequest> lines) {
    }

    public record UpdateOrderRequest(String customerRef, String currency, BigDecimal totalAmount, JsonNode attributes) {
    }

    public record UpdateLineRequest(BigDecimal quantity, BigDecimal unitPrice, String status, JsonNode attributes) {
    }

    public record OrderLineResponse(UUID lineId, int lineNumber, String itemRef, BigDecimal quantity,
                                     BigDecimal unitPrice, BigDecimal lineTotal, String status,
                                     JsonNode attributes, long version) {
    }

    public record OrderResponse(UUID orderId, String orderNumber, String orderTypeCode, String status,
                                 String customerRef, String currency, BigDecimal totalAmount, JsonNode attributes,
                                 long version, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                 List<OrderLineResponse> lines) {
    }
}
