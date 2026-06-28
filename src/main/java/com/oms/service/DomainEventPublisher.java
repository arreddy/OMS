package com.oms.service;

import com.oms.domain.event.DomainEvent;
import com.oms.repository.DomainEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Drains the domain_event outbox (SPEC.md §7.1). No real message broker is
 * wired up here — SPEC.md §9 leaves that choice open — so this stands in for
 * one by logging each event and marking it published, proving the outbox
 * plumbing end-to-end. Swap the body of publish() for a real broker call
 * (Kafka/SQS/etc.) once one is chosen; the polling/marking logic around it
 * stays the same.
 */
@Component
public class DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DomainEventPublisher.class);

    private final DomainEventRepository domainEventRepository;

    public DomainEventPublisher(DomainEventRepository domainEventRepository) {
        this.domainEventRepository = domainEventRepository;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.publish-interval-ms:5000}")
    @Transactional
    public void publishPending() {
        List<DomainEvent> pending = domainEventRepository.findTop100ByPublishedAtIsNullOrderByOccurredAtAsc();
        for (DomainEvent event : pending) {
            publish(event);
            event.setPublishedAt(OffsetDateTime.now());
        }
    }

    private void publish(DomainEvent event) {
        log.info("domain_event published type={} aggregateType={} aggregateId={} payload={}",
                event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
