package com.oms.repository;

import com.oms.domain.task.TaskComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

    List<TaskComment> findByTask_TaskIdOrderByCreatedAtAsc(UUID taskId);
}
