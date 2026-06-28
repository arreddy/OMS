package com.oms.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.workflow.TriggerType;
import com.oms.repository.DomainEventRepository;
import com.oms.web.dto.OrderDtos.CreateOrderRequest;
import com.oms.web.dto.OrderDtos.OrderResponse;
import com.oms.web.dto.OrderDtos.UpdateOrderRequest;
import com.oms.web.dto.TaskDtos.ApproveRequest;
import com.oms.web.dto.TaskDtos.RejectRequest;
import com.oms.web.dto.TaskDtos.TaskResponse;
import com.oms.web.dto.WorkflowDtos.FireTransitionRequest;
import com.oms.web.dto.WorkflowDtos.WorkflowInstanceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the STANDARD order type's seeded workflow (SPEC.md §4.6) end to
 * end through the real REST API, against a real Postgres via Testcontainers.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderWorkflowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DomainEventRepository domainEventRepository;

    @Test
    void lowAmountOrder_progressesAutomaticallyToDeliveredWithoutCreditReview() {
        OrderResponse order = createOrder(new BigDecimal("500.00"));
        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(domainEventRepository.findTop100ByPublishedAtIsNullOrderByOccurredAtAsc()).isNotEmpty();

        WorkflowInstanceResponse workflow = fireTransition(order.orderId(), TriggerType.EVENT, "order.submitted");
        assertThat(workflow.currentState()).isEqualTo("PAYMENT_PENDING");

        workflow = fireTransition(order.orderId(), TriggerType.EVENT, "payment.captured");
        assertThat(workflow.currentState()).isEqualTo("FULFILLMENT_QUEUED");

        workflow = fireTransition(order.orderId(), TriggerType.EVENT, "shipment.dispatched");
        assertThat(workflow.currentState()).isEqualTo("SHIPPED");

        workflow = fireTransition(order.orderId(), TriggerType.EVENT, "shipment.delivered");
        assertThat(workflow.currentState()).isEqualTo("DELIVERED");
        assertThat(workflow.terminal()).isTrue();

        OrderResponse finalOrder = getOrder(order.orderId());
        assertThat(finalOrder.status()).isEqualTo("DELIVERED");
    }

    @Test
    void highAmountOrder_entersCreditReviewAndApprovalMovesToFulfillment() {
        OrderResponse order = createOrder(new BigDecimal("5000.00"));

        // amount > 1000 guard fires immediately on entering PAYMENT_PENDING — no
        // payment.captured needed to reach CREDIT_REVIEW.
        WorkflowInstanceResponse workflow = fireTransition(order.orderId(), TriggerType.EVENT, "order.submitted");
        assertThat(workflow.currentState()).isEqualTo("CREDIT_REVIEW");

        List<TaskResponse> tasks = queryTasks("status=UNASSIGNED&assigneeGroup=credit-team");
        TaskResponse task = tasks.stream().filter(t -> t.orderId().equals(order.orderId())).findFirst().orElseThrow();
        assertThat(task.taskType()).isEqualTo("CREDIT_REVIEW");
        assertThat(task.status().name()).isEqualTo("UNASSIGNED");

        TaskResponse claimed = claim(task.taskId(), task.version());
        assertThat(claimed.assigneeId()).isEqualTo("alice");
        assertThat(claimed.status().name()).isEqualTo("ASSIGNED");

        TaskResponse approved = approve(claimed.taskId(), claimed.version(), "looks fine");
        assertThat(approved.status().name()).isEqualTo("APPROVED");

        WorkflowInstanceResponse afterApproval = getWorkflow(order.orderId());
        assertThat(afterApproval.currentState()).isEqualTo("FULFILLMENT_QUEUED");
    }

    @Test
    void highAmountOrder_rejectionCancelsOrder() {
        OrderResponse order = createOrder(new BigDecimal("9000.00"));
        fireTransition(order.orderId(), TriggerType.EVENT, "order.submitted");

        List<TaskResponse> tasks = queryTasks("status=UNASSIGNED&assigneeGroup=credit-team");
        TaskResponse task = tasks.stream()
                .filter(t -> t.orderId().equals(order.orderId())).findFirst().orElseThrow();

        TaskResponse rejected = reject(task.taskId(), task.version(), "fraud risk");
        assertThat(rejected.status().name()).isEqualTo("REJECTED");

        WorkflowInstanceResponse workflow = getWorkflow(order.orderId());
        assertThat(workflow.currentState()).isEqualTo("CANCELLED");
        assertThat(workflow.terminal()).isTrue();

        assertThat(getOrder(order.orderId()).status()).isEqualTo("CANCELLED");
    }

    @Test
    void staleIfMatchOnOrderUpdateReturns409() {
        OrderResponse order = createOrder(new BigDecimal("100.00"));

        UpdateOrderRequest update = new UpdateOrderRequest("new-customer-ref", null, null, null);
        HttpHeaders headers = jsonHeaders();
        headers.set("If-Match", String.valueOf(order.version() + 999));
        ResponseEntity<String> response = rest.exchange("/orders/" + order.orderId(), HttpMethod.PATCH,
                new HttpEntity<>(update, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private OrderResponse createOrder(BigDecimal amount) {
        JsonNode attributes = objectMapper.valueToTree(java.util.Map.of("giftMessage", "hello"));
        CreateOrderRequest request = new CreateOrderRequest("STANDARD", "cust-1", "USD", amount, attributes, null);
        ResponseEntity<OrderResponse> response = rest.postForEntity("/orders", new HttpEntity<>(request, jsonHeaders()), OrderResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private OrderResponse getOrder(java.util.UUID orderId) {
        return rest.getForObject("/orders/" + orderId, OrderResponse.class);
    }

    private WorkflowInstanceResponse getWorkflow(java.util.UUID orderId) {
        return rest.getForObject("/orders/" + orderId + "/workflow", WorkflowInstanceResponse.class);
    }

    private WorkflowInstanceResponse fireTransition(java.util.UUID orderId, TriggerType triggerType, String triggerCode) {
        FireTransitionRequest request = new FireTransitionRequest(triggerType, triggerCode, null);
        ResponseEntity<WorkflowInstanceResponse> response = rest.postForEntity("/orders/" + orderId + "/workflow/transitions",
                new HttpEntity<>(request, jsonHeaders()), WorkflowInstanceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private TaskResponse claim(java.util.UUID taskId, long ifMatch) {
        HttpHeaders headers = jsonHeaders();
        headers.set("If-Match", String.valueOf(ifMatch));
        headers.set("X-User-Id", "alice");
        ResponseEntity<TaskResponse> response = rest.postForEntity("/tasks/" + taskId + "/claim",
                new HttpEntity<>(null, headers), TaskResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private TaskResponse approve(java.util.UUID taskId, long ifMatch, String comment) {
        HttpHeaders headers = jsonHeaders();
        headers.set("If-Match", String.valueOf(ifMatch));
        ApproveRequest body = new ApproveRequest(comment);
        ResponseEntity<TaskResponse> response = rest.postForEntity("/tasks/" + taskId + "/approve",
                new HttpEntity<>(body, headers), TaskResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private TaskResponse reject(java.util.UUID taskId, long ifMatch, String reason) {
        HttpHeaders headers = jsonHeaders();
        headers.set("If-Match", String.valueOf(ifMatch));
        RejectRequest body = new RejectRequest(reason);
        ResponseEntity<TaskResponse> response = rest.postForEntity("/tasks/" + taskId + "/reject",
                new HttpEntity<>(body, headers), TaskResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** GET /tasks returns a Spring Data Page<TaskResponse> JSON object, not a bare array. */
    private List<TaskResponse> queryTasks(String query) {
        ResponseEntity<PageEnvelope<TaskResponse>> response = rest.exchange("/tasks?" + query, HttpMethod.GET, null,
                new ParameterizedTypeReference<PageEnvelope<TaskResponse>>() {
                });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().content();
    }

    private record PageEnvelope<T>(List<T> content) {
    }
}
