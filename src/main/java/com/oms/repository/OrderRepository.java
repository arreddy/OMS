package com.oms.repository;

import com.oms.domain.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByOrderNumber(String orderNumber);

    /** Backs the default order_number scheme — see V1__init_schema.sql order_number_seq. */
    @Query(value = "select nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumberSequence();

    static Specification<Order> hasStatus(String status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Order> hasOrderTypeCode(String orderTypeCode) {
        return (root, query, cb) -> orderTypeCode == null ? null : cb.equal(root.get("orderTypeCode"), orderTypeCode);
    }

    static Specification<Order> hasCustomerRef(String customerRef) {
        return (root, query, cb) -> customerRef == null ? null : cb.equal(root.get("customerRef"), customerRef);
    }
}
