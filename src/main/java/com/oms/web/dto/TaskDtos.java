package com.oms.web.dto;

import com.oms.domain.task.TaskDecision;
import com.oms.domain.task.TaskStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record AssignRequest(String assigneeId) {
    }

    public record ApproveRequest(String comment) {
    }

    public record RejectRequest(String reason) {
    }

    public record EscalateRequest(String reason) {
    }

    public record CommentRequest(String body) {
    }

    public record TaskResponse(UUID taskId, UUID orderId, UUID workflowInstanceId, String taskType, TaskStatus status,
                                String assigneeId, String assigneeGroup, short priority, OffsetDateTime slaDueAt,
                                TaskDecision decision, String decisionReason, String decisionBy,
                                String escalationReason, OffsetDateTime createdAt, OffsetDateTime claimedAt,
                                OffsetDateTime completedAt, long version) {
    }

    public record TaskCommentResponse(UUID commentId, String authorId, String body, OffsetDateTime createdAt) {
    }
}
