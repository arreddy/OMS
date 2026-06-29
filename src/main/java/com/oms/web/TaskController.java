package com.oms.web;

import com.oms.domain.task.Task;
import com.oms.domain.task.TaskComment;
import com.oms.domain.task.TaskStatus;
import com.oms.service.TaskService;
import com.oms.web.dto.TaskDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * SPEC.md §6 (Tasks). Endpoints are @Transactional even though TaskService's
 * methods already are: Task#order and Task#workflowInstance are lazy
 * associations touched by toResponse() below, which runs after the service
 * call returns — keeping the session open for that mapping step is the point
 * (open-in-view is disabled, so without this the lazy access would fail).
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Page<TaskResponse> list(@RequestParam(required = false) TaskStatus status,
                                    @RequestParam(required = false) String assigneeGroup,
                                    @RequestParam(required = false) String orderType,
                                    @RequestParam(required = false) String assigneeId,
                                    @RequestParam(required = false) Short priority,
                                    @RequestParam(required = false) UUID orderId,
                                    Pageable pageable) {
        return taskService.listTasks(status, assigneeGroup, orderType, assigneeId, priority, orderId, pageable).map(this::toResponse);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public TaskResponse get(@PathVariable UUID id) {
        return toResponse(taskService.getTask(id));
    }

    @GetMapping("/{id}/comments")
    public List<TaskCommentResponse> comments(@PathVariable UUID id) {
        return taskService.getComments(id).stream().map(this::toCommentResponse).toList();
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskCommentResponse addComment(@PathVariable UUID id, @RequestBody CommentRequest request,
                                           @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        return toCommentResponse(taskService.addComment(id, actor, request.body()));
    }

    @PostMapping("/{id}/claim")
    @Transactional
    public TaskResponse claim(@PathVariable UUID id, @RequestHeader("If-Match") long ifMatch,
                               @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        return toResponse(taskService.claim(id, actor, ifMatch));
    }

    @PostMapping("/{id}/assign")
    @Transactional
    public TaskResponse assign(@PathVariable UUID id, @RequestBody AssignRequest request,
                                @RequestHeader("If-Match") long ifMatch) {
        return toResponse(taskService.assign(id, request.assigneeId(), ifMatch));
    }

    @PostMapping("/{id}/approve")
    @Transactional
    public TaskResponse approve(@PathVariable UUID id, @RequestBody ApproveRequest request,
                                 @RequestHeader("If-Match") long ifMatch,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        return toResponse(taskService.approve(id, request.comment(), ifMatch, actor));
    }

    @PostMapping("/{id}/reject")
    @Transactional
    public TaskResponse reject(@PathVariable UUID id, @RequestBody RejectRequest request,
                                @RequestHeader("If-Match") long ifMatch,
                                @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        return toResponse(taskService.reject(id, request.reason(), ifMatch, actor));
    }

    @PostMapping("/{id}/escalate")
    @Transactional
    public TaskResponse escalate(@PathVariable UUID id, @RequestBody EscalateRequest request,
                                  @RequestHeader("If-Match") long ifMatch,
                                  @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        return toResponse(taskService.escalate(id, ifMatch, actor, request.reason()));
    }

    private TaskResponse toResponse(Task task) {
        return new TaskResponse(task.getTaskId(), task.getOrder().getOrderId(), task.getWorkflowInstance().getInstanceId(),
                task.getTaskType(), task.getStatus(), task.getAssigneeId(), task.getAssigneeGroup(), task.getPriority(),
                task.getSlaDueAt(), task.getDecision(), task.getDecisionReason(), task.getDecisionBy(),
                task.getEscalationReason(), task.getCreatedAt(), task.getClaimedAt(), task.getCompletedAt(), task.getVersion());
    }

    private TaskCommentResponse toCommentResponse(TaskComment comment) {
        return new TaskCommentResponse(comment.getCommentId(), comment.getAuthorId(), comment.getBody(), comment.getCreatedAt());
    }
}
