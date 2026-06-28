package com.oms.repository;

import com.oms.domain.event.DomainEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DomainEventRepository extends JpaRepository<DomainEvent, UUID> {

    List<DomainEvent> findTop100ByPublishedAtIsNullOrderByOccurredAtAsc();
}
