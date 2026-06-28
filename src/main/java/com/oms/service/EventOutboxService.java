package com.oms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.event.AggregateType;
import com.oms.domain.event.DomainEvent;
import com.oms.repository.DomainEventRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * SPEC.md §7.1. record() must only ever be called from within a caller's
 * existing @Transactional method, so the outbox row commits atomically with
 * the state change it describes — that's the entire point of the pattern.
 */
@Service
public class EventOutboxService {

    private final DomainEventRepository domainEventRepository;
    private final ObjectMapper objectMapper;

    public EventOutboxService(DomainEventRepository domainEventRepository, ObjectMapper objectMapper) {
        this.domainEventRepository = domainEventRepository;
        this.objectMapper = objectMapper;
    }

    public void record(String eventType, AggregateType aggregateType, UUID aggregateId, Map<String, Object> payload) {
        DomainEvent event = new DomainEvent();
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(toJson(payload));
        domainEventRepository.save(event);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event payload", e);
        }
    }
}
