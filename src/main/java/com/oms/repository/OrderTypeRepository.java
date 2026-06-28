package com.oms.repository;

import com.oms.domain.order.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderTypeRepository extends JpaRepository<OrderType, UUID> {

    Optional<OrderType> findByCode(String code);

    List<OrderType> findAllByActiveTrue();
}
