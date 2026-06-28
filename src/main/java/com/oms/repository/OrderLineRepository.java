package com.oms.repository;

import com.oms.domain.order.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    List<OrderLine> findByOrder_OrderIdOrderByLineNumberAsc(UUID orderId);

    Optional<OrderLine> findTopByOrder_OrderIdOrderByLineNumberDesc(UUID orderId);
}
