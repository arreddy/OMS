package com.oms.repository;

import com.oms.domain.order.Order;
import com.oms.domain.task.Task;
import com.oms.domain.task.TaskStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    /** Backs the default order_number scheme — see V1__init_schema.sql order_number_seq. */
    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumberSequence();

    /** Status filter on the order list (UI spec §2.1) is multi-select — null/empty means no filter. */
    static Specification<Order> hasStatusIn(List<String> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty() ? null : root.get("status").in(statuses);
    }

    static Specification<Order> hasOrderTypeCodeIn(List<String> orderTypeCodes) {
        return (root, query, cb) -> orderTypeCodes == null || orderTypeCodes.isEmpty() ? null
                : root.get("orderTypeCode").in(orderTypeCodes);
    }

    static Specification<Order> hasCustomerRef(String customerRef) {
        return (root, query, cb) -> customerRef == null ? null : cb.equal(root.get("customerRef"), customerRef);
    }

    static Specification<Order> createdBetween(OffsetDateTime from, OffsetDateTime to) {
        return (root, query, cb) -> {
            if (from != null && to != null) {
                return cb.between(root.get("createdAt"), from, to);
            }
            if (from != null) {
                return cb.greaterThanOrEqualTo(root.get("createdAt"), from);
            }
            if (to != null) {
                return cb.lessThanOrEqualTo(root.get("createdAt"), to);
            }
            return null;
        };
    }

    /** Backs the "Has open task" toggle (UI spec §2.1) — null means no filter either way. */
    static Specification<Order> hasOpenTask(Boolean hasOpenTask) {
        return (root, query, cb) -> {
            if (hasOpenTask == null) {
                return null;
            }
            Subquery<Long> subquery = query.subquery(Long.class);
            var taskRoot = subquery.from(Task.class);
            subquery.select(cb.literal(1L))
                    .where(cb.equal(taskRoot.get("order"), root),
                            taskRoot.get("status").in(List.of(
                                    TaskStatus.UNASSIGNED, TaskStatus.ASSIGNED, TaskStatus.IN_PROGRESS, TaskStatus.ESCALATED)));
            Predicate exists = cb.exists(subquery);
            return hasOpenTask ? exists : cb.not(exists);
        };
    }
}
