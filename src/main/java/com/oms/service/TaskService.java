package com.oms.service;

import com.oms.domain.event.AggregateType;
import com.oms.domain.task.Task;
import com.oms.domain.task.TaskComment;
import com.oms.domain.task.TaskDecision;
import com.oms.domain.task.TaskStatus;
import com.oms.exception.ConflictException;
import com.oms.exception.NotFoundException;
import com.oms.repository.TaskCommentRepository;
import com.oms.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SPEC.md §5 (Manual Task / Human Task Queue). */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskCommentRepository taskCommentRepository;
    private final WorkflowEngineService workflowEngineService;
    private final EventOutboxService eventOutboxService;

    public TaskService(TaskRepository taskRepository,
                        TaskCommentRepository taskCommentRepository,
                        WorkflowEngineService workflowEngineService,
                        EventOutboxService eventOutboxService) {
        this.taskRepository = taskRepository;
        this.taskCommentRepository = taskCommentRepository;
        this.workflowEngineService = workflowEngineService;
        this.eventOutboxService = eventOutboxService;
    }

    public Task getTask(UUID taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new NotFoundException("No task with id " + taskId));
    }

    public Page<Task> listTasks(TaskStatus status, String assigneeGroup, String orderTypeCode, Pageable pageable) {
        Specification<Task> spec = Specification.allOf(
                TaskRepository.hasStatus(status),
                TaskRepository.hasAssigneeGroup(assigneeGroup),
                TaskRepository.hasOrderTypeCode(orderTypeCode));
        return taskRepository.findAll(spec, pageable);
    }

    public List<TaskComment> getComments(UUID taskId) {
        return taskCommentRepository.findByTask_TaskIdOrderByCreatedAtAsc(taskId);
    }

    @Transactional
    public TaskComment addComment(UUID taskId, String authorId, String body) {
        Task task = getTask(taskId);
        TaskComment comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthorId(authorId);
        comment.setBody(body);
        return taskCommentRepository.save(comment);
    }

    @Transactional
    public Task claim(UUID taskId, String userId, long expectedVersion) {
        Task task = getTask(taskId);
        requireVersionMatch(task, expectedVersion);
        requireOpen(task);
        task.setAssigneeId(userId);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setClaimedAt(OffsetDateTime.now());
        task = taskRepository.save(task);
        recordAssigned(task);
        return task;
    }

    @Transactional
    public Task assign(UUID taskId, String assigneeId, long expectedVersion) {
        Task task = getTask(taskId);
        requireVersionMatch(task, expectedVersion);
        requireOpen(task);
        task.setAssigneeId(assigneeId);
        task.setStatus(TaskStatus.ASSIGNED);
        task.setClaimedAt(OffsetDateTime.now());
        task = taskRepository.save(task);
        recordAssigned(task);
        return task;
    }

    @Transactional
    public Task approve(UUID taskId, String comment, long expectedVersion, String actor) {
        Task task = getTask(taskId);
        requireVersionMatch(task, expectedVersion);
        requireOpen(task);

        task.setDecision(TaskDecision.APPROVE);
        task.setDecisionReason(comment);
        task.setDecisionBy(actor);
        task.setStatus(TaskStatus.APPROVED);
        task.setCompletedAt(OffsetDateTime.now());
        task = taskRepository.save(task);

        workflowEngineService.fireTaskDecision(task, TaskDecision.APPROVE, actor, comment);

        eventOutboxService.record("task.approved", AggregateType.TASK, task.getTaskId(),
                Map.of("taskId", task.getTaskId().toString(), "occurredAt", OffsetDateTime.now().toString(), "triggeredBy", actor));
        return task;
    }

    @Transactional
    public Task reject(UUID taskId, String reason, long expectedVersion, String actor) {
        Task task = getTask(taskId);
        requireVersionMatch(task, expectedVersion);
        requireOpen(task);

        task.setDecision(TaskDecision.REJECT);
        task.setDecisionReason(reason);
        task.setDecisionBy(actor);
        task.setStatus(TaskStatus.REJECTED);
        task.setCompletedAt(OffsetDateTime.now());
        task = taskRepository.save(task);

        workflowEngineService.fireTaskDecision(task, TaskDecision.REJECT, actor, reason);

        eventOutboxService.record("task.rejected", AggregateType.TASK, task.getTaskId(),
                Map.of("taskId", task.getTaskId().toString(), "occurredAt", OffsetDateTime.now().toString(), "triggeredBy", actor));
        return task;
    }

    @Transactional
    public Task escalate(UUID taskId, long expectedVersion, String actor) {
        Task task = getTask(taskId);
        requireVersionMatch(task, expectedVersion);
        requireOpen(task);
        task.setStatus(TaskStatus.ESCALATED);
        task = taskRepository.save(task);
        recordEscalated(task);
        return task;
    }

    /**
     * SPEC.md §5.3 step 4. Escalation policy configuration is an explicit
     * open extension point (SPEC.md §9) — this default just raises priority
     * and drops the direct assignee so the task falls back to its
     * assignee_group queue, rather than silently sitting unowned.
     */
    @Scheduled(fixedDelayString = "${oms.task.sla-sweep-interval-ms:30000}")
    @Transactional
    public void sweepSlaBreaches() {
        List<Task> overdue = taskRepository.findByStatusInAndSlaDueAtBefore(
                List.of(TaskStatus.UNASSIGNED, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS), OffsetDateTime.now());
        for (Task task : overdue) {
            task.setStatus(TaskStatus.ESCALATED);
            task.setAssigneeId(null);
            task.setPriority((short) Math.max(0, task.getPriority() - 1));
            taskRepository.save(task);
            recordEscalated(task);
        }
    }

    private void requireOpen(Task task) {
        if (task.getStatus() == TaskStatus.APPROVED || task.getStatus() == TaskStatus.REJECTED
                || task.getStatus() == TaskStatus.CANCELLED) {
            throw new ConflictException("Task " + task.getTaskId() + " is already finalized (" + task.getStatus() + ")");
        }
    }

    private void requireVersionMatch(Task task, long expectedVersion) {
        if (task.getVersion() != expectedVersion) {
            throw new ConflictException("Version mismatch on task " + task.getTaskId()
                    + ": expected " + expectedVersion + " but was " + task.getVersion());
        }
    }

    private void recordAssigned(Task task) {
        eventOutboxService.record("task.assigned", AggregateType.TASK, task.getTaskId(),
                Map.of("taskId", task.getTaskId().toString(), "assigneeId", String.valueOf(task.getAssigneeId()),
                        "occurredAt", OffsetDateTime.now().toString()));
    }

    private void recordEscalated(Task task) {
        eventOutboxService.record("task.escalated", AggregateType.TASK, task.getTaskId(),
                Map.of("taskId", task.getTaskId().toString(), "occurredAt", OffsetDateTime.now().toString()));
    }
}
