# Order Management System — Technical Documentation

> Companion to [SPEC.md](SPEC.md), [UI-SPEC.md](UI-SPEC.md), and [GUIDE.md](GUIDE.md).
> This document covers system architecture, data model, service internals, all API
> endpoints, and end-to-end sequence diagrams for every major flow.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Technology Stack](#2-technology-stack)
3. [Project Layout](#3-project-layout)
4. [Data Model](#4-data-model)
5. [Backend Architecture](#5-backend-architecture)
6. [API Reference](#6-api-reference)
7. [Sequence Diagrams](#7-sequence-diagrams)
   - 7.1 [Order Creation](#71-order-creation)
   - 7.2 [Workflow Transition (Event / API Action)](#72-workflow-transition-event--api-action)
   - 7.3 [Low-Value Order — Full Automated Lifecycle](#73-low-value-order--full-automated-lifecycle)
   - 7.4 [High-Value Order — Guard → Manual Review](#74-high-value-order--guard--manual-review)
   - 7.5 [Task Claim and Approve](#75-task-claim-and-approve)
   - 7.6 [Task Reject](#76-task-reject)
   - 7.7 [Task Escalation (Manual)](#77-task-escalation-manual)
   - 7.8 [SLA Sweep — Automatic Escalation](#78-sla-sweep--automatic-escalation)
   - 7.9 [Publish Workflow Definition](#79-publish-workflow-definition)
   - 7.10 [Optimistic Lock Conflict](#710-optimistic-lock-conflict)
   - 7.11 [Transactional Outbox — Event Delivery](#711-transactional-outbox--event-delivery)
   - 7.12 [Order Schema Extension (PATCH)](#712-order-schema-extension-patch)
   - 7.13 [Tenant Resolution](#713-tenant-resolution)
8. [Workflow Engine Deep Dive](#8-workflow-engine-deep-dive)
9. [Human Task Queue](#9-human-task-queue)
10. [Event System](#10-event-system)
11. [Frontend Architecture](#11-frontend-architecture)
12. [Concurrency & Consistency](#12-concurrency--consistency)
13. [Multi-Tenancy](#13-multi-tenancy)

---

## 1. System Overview

The OMS is a self-contained, multi-tenant order management system composed of four concerns:

| Concern | Description |
|---|---|
| **Order model** | Fixed typed columns (`order_id`, `total_amount`, etc.) plus a schema-validated JSONB extension bag (`attributes`) per order type — no EAV, no migrations for new fields. |
| **Workflow engine** | A configurable state machine (not a hardcoded status enum). Each order type carries its own versioned `workflow_definition`; every order gets a pinned `workflow_instance` that runs independently of later definition changes. |
| **Human task queue** | `MANUAL` workflow states automatically generate tasks. Agents claim, approve, or reject tasks; decisions are translated back into workflow transition triggers. |
| **Multi-tenancy** | Every order-type, workflow, order, and task row carries a `tenant_id`, resolved per request from `X-Tenant-Id` and enforced automatically by Hibernate (§13) — no tenant-aware code in any service. |

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              React Frontend                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐ │
│  │  Ops Console │  │ Task Queue   │  │  Admin Console │  │ Customer     │ │
│  │  /ops/orders │  │ /ops/tasks   │  │ /admin/order-  │  │ Portal       │ │
│  │              │  │              │  │  types         │  │ /track/:id   │ │
│  └──────┬───────┘  └──────┬───────┘  └───────┬────────┘  └──────┬───────┘ │
└─────────┼─────────────────┼──────────────────┼─────────────────┼───────────┘
          │  HTTP / Vite proxy (:5173 → :8080)  │                 │
          ▼                 ▼                   ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Spring Boot REST API (:8080)                        │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐ │
│  │ OrderCtrl    │  │ TaskCtrl     │  │ OrderTypeCtrl  │  │ WorkflowCtrl │ │
│  └──────┬───────┘  └──────┬───────┘  └───────┬────────┘  └──────┬───────┘ │
│         │                 │                   │                   │         │
│  ┌──────▼───────┐  ┌──────▼───────┐  ┌───────▼────────┐         │         │
│  │ OrderService │  │ TaskService  │  │ OrderTypeService│         │         │
│  └──────┬───────┘  └──────┬───────┘  └────────────────┘         │         │
│         │                 │                                        │         │
│         └──────────┬──────┘ ──────────────────────────────────────┘         │
│                    ▼                                                          │
│         ┌──────────────────────┐   ┌───────────────────┐                    │
│         │ WorkflowEngineService│   │ EventOutboxService│                    │
│         │  + GuardEvaluator    │   │                   │                    │
│         └──────────┬───────────┘   └─────────┬─────────┘                   │
└────────────────────┼──────────────────────────┼────────────────────────────┘
                     │  JPA / Spring Data        │
                     ▼                           ▼
              ┌─────────────────────────────────────────┐
              │         PostgreSQL 16 (:5432)            │
              │  order, order_line, order_type,          │
              │  workflow_definition/state/transition,   │
              │  workflow_instance, workflow_transition_ │
              │  log, task, task_comment, domain_event,  │
              │  tenant                                  │
              └─────────────────────────────────────────┘
```

Not pictured above: a `TenantFilter` runs in front of every controller (highest-precedence servlet filter), resolving `X-Tenant-Id` into a `ThreadLocal` that Hibernate reads to scope every query/insert against the tables in the box above (except `tenant` itself). See §13.

---

## 2. Technology Stack

| Layer | Technology |
|---|---|
| **Backend runtime** | Java 21, Spring Boot 3 |
| **Persistence** | PostgreSQL 16, Spring Data JPA / Hibernate, Flyway migrations |
| **Guard evaluation** | JSON Logic (safe-by-construction, no arbitrary code execution) |
| **Schema validation** | JSON Schema (via `JsonSchemaValidationService`) |
| **Multi-tenancy** | Hibernate 6 `@TenantId` (discriminator-column tenant isolation, §13) |
| **API docs** | SpringDoc / OpenAPI 3 — auto-generated, Swagger UI at `/swagger-ui/index.html` |
| **Frontend** | React 18, TypeScript, Vite (dev proxy to `:8080`) |
| **Testing** | JUnit 5, Testcontainers (Postgres) for integration tests |
| **Containerization** | Docker Compose (Postgres for local dev) |

---

## 3. Project Layout

```
OMS/
├── src/main/java/com/oms/
│   ├── OmsApplication.java
│   ├── config/           OpenApiConfig
│   ├── domain/
│   │   ├── event/        DomainEvent, AggregateType
│   │   ├── order/        Order, OrderLine, OrderType
│   │   ├── task/         Task, TaskComment, TaskDecision, TaskStatus
│   │   ├── tenant/       Tenant (registry row — itself not tenant-scoped)
│   │   └── workflow/     WorkflowDefinition, WorkflowState, WorkflowTransition,
│   │                     WorkflowInstance, WorkflowTransitionLog,
│   │                     StateType, TriggerType, TerminalOutcome, BadgeCategory
│   ├── exception/        ConflictException, NotFoundException
│   ├── repository/       One Spring Data repo per aggregate root, plus TenantRepository
│   ├── service/
│   │   ├── OrderService.java
│   │   ├── OrderTypeService.java
│   │   ├── TaskService.java
│   │   ├── WorkflowEngineService.java
│   │   ├── EventOutboxService.java
│   │   ├── DomainEventPublisher.java
│   │   ├── guard/        GuardEvaluator (JSON Logic)
│   │   └── validation/   JsonSchemaValidationService, SchemaValidationException
│   ├── tenant/           TenantContext, TenantFilter, SpringTenantIdentifierResolver,
│   │                     TenantHibernateConfig (§13)
│   └── web/
│       ├── GlobalExceptionHandler.java
│       ├── OrderController.java
│       ├── OrderTypeController.java
│       ├── TaskController.java
│       ├── WorkflowController.java
│       └── dto/          OrderDtos, OrderTypeDtos, TaskDtos, WorkflowDtos
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/     Flyway SQL migrations (V1__init_schema, V2__seed_standard_order_type,
│                         V3__ui_surface_fields, V4__add_multi_tenancy)
├── src/test/java/com/oms/
│   └── integration/OrderWorkflowIT.java  (Testcontainers integration test)
├── web/                  React frontend (Vite)
│   └── src/
│       ├── admin/        OrderTypeEditorPage, OrderTypeListPage,
│       │                 WorkflowDesignerPage, SchemaBuilder, workflowGraph
│       ├── api/          orders.ts, tasks.ts, orderTypes.ts, workflow.ts
│       ├── components/   StatusBadge, SlaBadge, DynamicSchemaForm, Pagination, …
│       ├── customer/     OrderTrackingPage
│       ├── hooks/        useStatusTaxonomy
│       ├── layout/       OpsAdminLayout, CustomerLayout
│       ├── lib/          api.ts (fetch wrapper), actingUser, badgeColors
│       └── ops/          OrderListPage, OrderDetailPage, OrderCreatePage,
│                         TaskDetailPage, TaskQueuePage
├── docker-compose.yml
├── pom.xml
├── README.md, SPEC.md, UI-SPEC.md, GUIDE.md
└── TECHNICAL.md          (this file)
```

---

## 4. Data Model

### Entity Relationship Diagram

```mermaid
erDiagram
    TENANT {
        VARCHAR tenant_id PK
        VARCHAR name
        BOOLEAN is_active
    }

    ORDER_TYPE {
        UUID order_type_id PK
        VARCHAR tenant_id FK
        VARCHAR code UK "UNIQUE (tenant_id, code)"
        VARCHAR name
        JSONB attribute_schema
        JSONB line_attribute_schema
        UUID workflow_definition_id FK
        BOOLEAN is_active
    }

    ORDER {
        UUID order_id PK
        VARCHAR tenant_id FK
        VARCHAR order_number UK "globally unique, not per-tenant"
        VARCHAR order_type_code FK "composite FK (tenant_id, order_type_code)"
        VARCHAR status
        VARCHAR customer_ref
        CHAR currency
        NUMERIC total_amount
        JSONB attributes
        BIGINT version
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        VARCHAR created_by
        VARCHAR updated_by
    }

    ORDER_LINE {
        UUID line_id PK
        VARCHAR tenant_id FK
        UUID order_id FK
        INT line_number
        VARCHAR item_ref
        NUMERIC quantity
        NUMERIC unit_price
        NUMERIC line_total
        VARCHAR status
        JSONB attributes
        BIGINT version
    }

    WORKFLOW_DEFINITION {
        UUID workflow_definition_id PK
        VARCHAR tenant_id FK
        VARCHAR order_type_code
        INT version "UNIQUE (tenant_id, order_type_code, version)"
        VARCHAR name
        TIMESTAMPTZ published_at
    }

    WORKFLOW_STATE {
        UUID state_id PK
        VARCHAR tenant_id FK
        UUID workflow_definition_id FK
        VARCHAR code
        ENUM state_type
        BOOLEAN is_initial
        BOOLEAN is_terminal
        VARCHAR default_assignee_group
        BOOLEAN is_customer_visible
        VARCHAR customer_facing_label
        ENUM terminal_outcome
        NUMERIC canvas_x
        NUMERIC canvas_y
    }

    WORKFLOW_TRANSITION {
        UUID transition_id PK
        VARCHAR tenant_id FK
        UUID workflow_definition_id FK
        UUID from_state_id FK
        UUID to_state_id FK
        INT sequence
        ENUM trigger_type
        VARCHAR trigger_code
        TEXT guard_expression
        VARCHAR side_effect
    }

    WORKFLOW_INSTANCE {
        UUID instance_id PK
        VARCHAR tenant_id FK
        UUID order_id FK
        UUID workflow_definition_id FK
        UUID current_state_id FK
        TIMESTAMPTZ started_at
        TIMESTAMPTZ completed_at
        BIGINT version
    }

    WORKFLOW_TRANSITION_LOG {
        UUID log_id PK
        VARCHAR tenant_id FK
        UUID instance_id FK
        VARCHAR from_state_code
        VARCHAR to_state_code
        ENUM trigger_type
        VARCHAR trigger_code
        VARCHAR triggered_by
        TEXT comment
        TIMESTAMPTZ occurred_at
    }

    TASK {
        UUID task_id PK
        VARCHAR tenant_id FK
        UUID order_id FK
        UUID workflow_instance_id FK
        UUID state_id FK
        VARCHAR task_type
        ENUM status
        VARCHAR assignee_id
        VARCHAR assignee_group
        SMALLINT priority
        TIMESTAMPTZ sla_due_at
        ENUM decision
        TEXT decision_reason
        VARCHAR decision_by
        TEXT escalation_reason
        TIMESTAMPTZ created_at
        TIMESTAMPTZ claimed_at
        TIMESTAMPTZ completed_at
        BIGINT version
    }

    TASK_COMMENT {
        UUID comment_id PK
        VARCHAR tenant_id FK
        UUID task_id FK
        VARCHAR author_id
        TEXT body
        TIMESTAMPTZ created_at
    }

    DOMAIN_EVENT {
        UUID event_id PK
        VARCHAR tenant_id FK
        VARCHAR event_type
        ENUM aggregate_type
        UUID aggregate_id
        JSONB payload
        TIMESTAMPTZ occurred_at
        TIMESTAMPTZ published_at
    }

    TENANT ||--o{ ORDER_TYPE : "tenant_id"
    TENANT ||--o{ ORDER : "tenant_id"
    TENANT ||--o{ ORDER_LINE : "tenant_id"
    TENANT ||--o{ WORKFLOW_DEFINITION : "tenant_id"
    TENANT ||--o{ WORKFLOW_STATE : "tenant_id"
    TENANT ||--o{ WORKFLOW_TRANSITION : "tenant_id"
    TENANT ||--o{ WORKFLOW_INSTANCE : "tenant_id"
    TENANT ||--o{ WORKFLOW_TRANSITION_LOG : "tenant_id"
    TENANT ||--o{ TASK : "tenant_id"
    TENANT ||--o{ TASK_COMMENT : "tenant_id"
    TENANT ||--o{ DOMAIN_EVENT : "tenant_id"
    ORDER_TYPE ||--o{ ORDER : "(tenant_id, order_type_code)"
    ORDER_TYPE ||--o| WORKFLOW_DEFINITION : "workflow_definition_id (active version)"
    ORDER ||--o{ ORDER_LINE : "order_id"
    ORDER ||--|| WORKFLOW_INSTANCE : "order_id"
    WORKFLOW_DEFINITION ||--o{ WORKFLOW_STATE : "workflow_definition_id"
    WORKFLOW_DEFINITION ||--o{ WORKFLOW_TRANSITION : "workflow_definition_id"
    WORKFLOW_STATE ||--o{ WORKFLOW_TRANSITION : "from_state_id"
    WORKFLOW_STATE ||--o{ WORKFLOW_TRANSITION : "to_state_id"
    WORKFLOW_INSTANCE ||--|| WORKFLOW_STATE : "current_state_id"
    WORKFLOW_INSTANCE ||--|| WORKFLOW_DEFINITION : "workflow_definition_id (pinned)"
    WORKFLOW_INSTANCE ||--o{ WORKFLOW_TRANSITION_LOG : "instance_id"
    ORDER ||--o{ TASK : "order_id"
    WORKFLOW_INSTANCE ||--o{ TASK : "workflow_instance_id"
    WORKFLOW_STATE ||--o{ TASK : "state_id"
    TASK ||--o{ TASK_COMMENT : "task_id"
```

### Key Schema Decisions

| Decision | Rationale |
|---|---|
| `order.status` denormalized from workflow | Fast `WHERE status = ?` filtering without joining workflow tables on every read. Only `WorkflowEngineService` writes it. |
| `order_type.workflow_definition_id` as sole "active version" pointer | Eliminates the dual-flag ambiguity of having both a FK and a per-row `is_active`. Publishing atomically repoints this FK. |
| `workflow_instance.workflow_definition_id` pinned at creation | In-flight orders are never silently affected by a new workflow version. |
| `attributes` / `line_attributes` as JSONB | Avoids EAV join explosion; new fields are schema changes, not DDL migrations. |
| Optimistic locks (`version` BIGINT) on `order`, `workflow_instance`, `task`, `order_line` | Concurrent task decisions and automated transitions can race on the same order; each lock is independent (SPEC.md §8). |
| `tenant_id` as a discriminator column, not schema- or database-per-tenant | One Postgres schema, one Flyway migration path, one connection pool — isolation is enforced by Hibernate (§13), not by infrastructure separation. Revisit if a compliance requirement ever demands physical isolation. |

### Why `attributes` lives in the same `order` row (not a separate table)

The alternative is an **EAV (Entity-Attribute-Value)** table — a classic pattern that looks like this:

```
order_attribute
  order_id  │  key          │  value
────────────┼───────────────┼─────────
  uuid-1    │  giftMessage  │  "hi"
  uuid-1    │  creditScore  │  "720"
  uuid-2    │  giftMessage  │  "happy bday"
```

EAV has three structural problems that the JSONB column avoids:

**1. Join explosion on every read.** Fetching one order with 5 attributes requires 5 joined rows that must be pivoted back into a map in application code. At any scale this becomes expensive and verbose. The JSONB column returns a single row — no joins, no pivots.

**2. All values become strings.** EAV tables store everything as `VARCHAR`. You lose type fidelity: comparing `"720" > "70"` as strings gives the wrong answer. Encoding and decoding types yourself is fragile and not enforced by the DB. JSONB preserves types natively — numbers are numbers, booleans are booleans.

**3. Schema is implicit and unenforced.** An EAV table has no way to express "a `STANDARD` order must have a `creditScore` field of type `number`" vs. "a `SUBSCRIPTION` order has a `billingCycleDay` field of type `integer`." Any key can appear against any order and the DB cannot reject it. With JSONB, `order_type.attribute_schema` holds a JSON Schema per order type, and `JsonSchemaValidationService` validates every write against it — invalid attributes are rejected with `400` at the API boundary.

**The accepted tradeoff:** JSONB fields are not individually indexed by default, so `WHERE attributes->>'creditScore' > '700'` is a sequential scan unless you add a specific expression index. This is acceptable here because the fields that need to be fast-filtered (`status`, `customer_ref`, `total_amount`, `order_type_code`) are all real columns. Attribute values are context the order carries for its workflow and task reviewers — they are read per-order, not used in large cross-order aggregations.

The two tiers serve different purposes: **real columns for what the system queries on, JSONB for what the order type owns and the system passes through.**

### JSONB in the same row vs. JSONB in a separate table

A subtler alternative to EAV is keeping JSONB but moving it to a dedicated side table:

```sql
-- option A: current design
orders
  order_id     UUID
  status       VARCHAR
  total_amount NUMERIC
  attributes   JSONB

-- option B: side table
orders
  order_id     UUID
  status       VARCHAR
  total_amount NUMERIC

order_attributes
  order_id     UUID FK
  attributes   JSONB
```

Both options avoid the EAV problems above (type fidelity is preserved, schema is enforced at the app layer either way, GIN indexing is available either way). The difference is purely physical:

| Dimension | Same row (current) | Side table |
|---|---|---|
| **Read** | Single row fetch, zero joins | Always a `LEFT JOIN` on every order fetch |
| **Write** | One `INSERT`/`UPDATE` | Two writes — must be wrapped in a transaction to stay atomic |
| **Atomicity** | Order + attributes always commit together | Requires explicit transaction; two rows can drift if not handled carefully |
| **Heap size** | `attributes` blob inflates the orders heap page | Orders heap stays lean; blob lives in a separate heap |
| **TOAST** | Postgres moves large blobs out-of-line automatically once they exceed ~2 KB | Same TOAST behaviour applies to the side table's JSONB column |

**The only real argument for a side table is heap pressure.** If `attributes` were routinely several kilobytes, keeping it inline would inflate the orders heap page, which hurts sequential scans and index lookups on `status`, `order_type_code`, and other columns that are filtered constantly. A side table keeps those rows tight.

In practice, Postgres TOAST already neutralises this. Once `attributes` exceeds ~2 KB it is compressed and moved out-of-line regardless — the main heap row stores only a pointer. The physical separation the side table offers is therefore obtained automatically, without the join.

**For this codebase the values are small** — a handful of order-type-specific fields (`giftMessage`, `priorityShipping`, `creditScore`). TOAST never triggers. The side table would add a join on every read and a second write on every mutation, for no benefit.

**What a side table cannot solve either.** If per-attribute change history is ever needed (e.g. tracking that `creditScore` changed from 680 to 720 on a specific date), neither a same-row JSONB column nor a JSONB side table can provide it — the whole blob is overwritten on each update. That would require a separate audit/history table with one row per attribute change, which is a different design (closer to event sourcing) and outside the current scope.

---

## 5. Backend Architecture

### Service Layer Dependency Graph

```
OrderController
    └── OrderService
            ├── OrderTypeService         (validates schema, resolves order type)
            ├── WorkflowEngineService    (starts instance, fires transitions)
            ├── JsonSchemaValidationService
            └── EventOutboxService       (records domain events)

TaskController
    └── TaskService
            ├── WorkflowEngineService    (fires TASK_APPROVED / TASK_REJECTED)
            └── EventOutboxService

OrderTypeController
    └── OrderTypeService
            ├── WorkflowDefinitionRepository
            ├── WorkflowStateRepository
            └── WorkflowTransitionRepository

WorkflowController
    └── WorkflowEngineService / repositories (read-only)
```

**Circular dependency avoidance:** `WorkflowEngineService` depends directly on `TaskRepository` (not `TaskService`) so that `TaskService → WorkflowEngineService` stays unidirectional. `TaskService` calls back into the engine on approve/reject; a two-way service dependency would create a circular Spring bean graph.

### Guard Evaluation

```
GuardEvaluator.evaluate(guardExpression, context)
    context = { "order": { orderId, orderNumber, orderTypeCode, status,
                           customerRef, currency, totalAmount, attributes } }

    null expression  → always true (transition is unconditional)
    JSON Logic expr  → evaluated against context, must return boolean
    Example:  {">": [{"var": "order.totalAmount"}, 1000]}  → true when amount > 1000
```

### Auto-Progress (chained transitions)

After every `startInstance`, `fireTrigger`, or `fireTaskDecision`, the engine calls `runAutoProgress`:

```
loop (up to MAX_AUTO_PROGRESS_HOPS = 50):
    if current state is terminal or MANUAL → stop
    find first outbound transition where:
        trigger_code IS NULL   (no external signal required)
        AND guard passes
    if found → applyTransition("SYSTEM") → continue loop
    else → stop
```

This is how guard-only branches work: a state with two outbound null-trigger transitions with opposing guards fires the matching one immediately on entry, without waiting for an external event.

---

## 6. API Reference

Every endpoint below, in addition to whatever's listed under "Auth header",
requires `X-Tenant-Id` — resolved by `TenantFilter` before any controller
runs (§13). It's omitted from the per-row "Auth header" column below since
it applies uniformly, the same way it isn't repeated in SPEC.md §6.

### Orders

| Method | Path | Auth header | Request | Response | Status |
|---|---|---|---|---|---|
| `POST` | `/orders` | `X-User-Id` | `{orderTypeCode, customerRef, currency, totalAmount, attributes?, lines?}` | `OrderResponse` | 201 |
| `GET` | `/orders/{id}` | — | — | `OrderResponse` (with lines) | 200 |
| `GET` | `/orders` | — | Query: `status[]`, `orderType[]`, `customerRef`, `createdFrom`, `createdTo`, `hasOpenTask`, Pageable | `Page<OrderResponse>` | 200 |
| `PATCH` | `/orders/{id}` | `X-User-Id`, `If-Match: <version>` | `{customerRef?, currency?, totalAmount?, attributes?}` | `OrderResponse` | 200 / 409 |
| `POST` | `/orders/{id}/lines` | — | `{itemRef, quantity, unitPrice, attributes?}` | `OrderLineResponse` | 201 |
| `PATCH` | `/orders/{id}/lines/{lineId}` | `If-Match: <version>` | `{quantity?, unitPrice?, status?, attributes?}` | `OrderLineResponse` | 200 / 409 |

### Workflow

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| `GET` | `/orders/{id}/workflow` | — | Current state, valid next transitions, full history | 200 |
| `POST` | `/orders/{id}/workflow/transitions` | `{triggerType, triggerCode}` | Updated workflow summary | 200 / 409 |
| `GET` | `/workflow-definitions/{id}` | — | States + transitions for a specific version | 200 |

### Order Types

| Method | Path | Request | Response | Status |
|---|---|---|---|---|
| `GET` | `/order-types` | — | `List<OrderTypeResponse>` | 200 |
| `GET` | `/order-types/status-taxonomy` | — | `Map<statusCode, badgeCategory>` across all active types | 200 |
| `GET` | `/order-types/{code}/schema` | — | `{attributeSchema, lineAttributeSchema, workflowSummary}` | 200 |
| `POST` | `/order-types` | `{code, name, attributeSchema, lineAttributeSchema}` | `OrderTypeResponse` | 201 |
| `PATCH` | `/order-types/{code}` | `{attributeSchema?, lineAttributeSchema?}` | `OrderTypeResponse` | 200 |
| `PUT` | `/order-types/{code}/workflow` | `{name, states[], transitions[]}` | `WorkflowDefinitionResponse` | 201 / 400 |

### Tasks

| Method | Path | Auth header | Request | Response | Status |
|---|---|---|---|---|---|
| `GET` | `/tasks` | — | Query: `status`, `assigneeGroup`, `orderType`, `assigneeId`, `priority`, `orderId`, Pageable | `Page<TaskResponse>` | 200 |
| `GET` | `/tasks/{id}` | — | — | `TaskResponse` | 200 |
| `GET` | `/tasks/{id}/comments` | — | — | `List<TaskCommentResponse>` | 200 |
| `POST` | `/tasks/{id}/comments` | `X-User-Id` | `{body}` | `TaskCommentResponse` | 201 |
| `POST` | `/tasks/{id}/claim` | `X-User-Id`, `If-Match` | — | `TaskResponse` | 200 / 409 |
| `POST` | `/tasks/{id}/assign` | `If-Match` | `{assigneeId}` | `TaskResponse` | 200 / 409 |
| `POST` | `/tasks/{id}/approve` | `X-User-Id`, `If-Match` | `{comment?}` | `TaskResponse` | 200 / 409 |
| `POST` | `/tasks/{id}/reject` | `X-User-Id`, `If-Match` | `{reason}` | `TaskResponse` | 200 / 409 |
| `POST` | `/tasks/{id}/escalate` | `X-User-Id`, `If-Match` | `{reason}` (required — 400 if blank) | `TaskResponse` | 200 / 400 / 409 |

### Error Responses

| HTTP Status | Trigger |
|---|---|
| 400 Bad Request | Schema validation failure, blank escalation reason, invalid workflow graph on publish, missing `X-Tenant-Id` header |
| 404 Not Found | Unknown order/task/order-type ID or code, or an `X-Tenant-Id` with no matching row in `tenant` |
| 409 Conflict | Optimistic lock version mismatch (`If-Match` header doesn't match stored `version`), transition not valid from current state, workflow already completed |

---

## 7. Sequence Diagrams

### 7.1 Order Creation

```mermaid
sequenceDiagram
    actor Client
    participant OC as OrderController
    participant OS as OrderService
    participant OTS as OrderTypeService
    participant JSV as JsonSchemaValidationService
    participant WE as WorkflowEngineService
    participant EO as EventOutboxService
    participant DB as PostgreSQL

    Client->>OC: POST /orders {orderTypeCode, customerRef, currency, totalAmount, attributes}
    OC->>OS: createOrder(command, actor)

    OS->>OTS: getByCode(orderTypeCode)
    OTS->>DB: SELECT order_type WHERE code = ?
    DB-->>OTS: OrderType (with attribute_schema, workflow_definition_id)
    OTS-->>OS: OrderType

    OS->>JSV: validate(attributeSchema, attributesJson)
    JSV-->>OS: OK (or throws SchemaValidationException → 400)

    OS->>DB: INSERT order (status = "INITIALIZING")
    DB-->>OS: Order (with generated order_id, order_number)

    OS->>WE: startInstance(order, orderType, actor)

    WE->>DB: SELECT workflow_state WHERE workflow_definition_id = ? AND is_initial = true
    DB-->>WE: WorkflowState (e.g. CREATED)

    WE->>DB: INSERT workflow_instance (current_state_id = CREATED, version = 0)
    WE->>DB: UPDATE order SET status = "CREATED"
    WE->>DB: INSERT workflow_transition_log (from=null, to=CREATED)
    WE->>EO: record("workflow.transitioned", toState=CREATED)
    EO->>DB: INSERT domain_event (published_at = null)

    Note over WE: runAutoProgress() — checks for null-trigger transitions from CREATED
    WE->>DB: SELECT transitions WHERE from_state = CREATED AND trigger_code IS NULL
    DB-->>WE: (none for CREATED — waits for external event)

    WE-->>OS: WorkflowInstance

    OS->>EO: record("order.created")
    EO->>DB: INSERT domain_event

    OS-->>OC: Order
    OC-->>Client: 201 {orderId, orderNumber, status: "CREATED", version: 0, …}
```

---

### 7.2 Workflow Transition (Event / API Action)

```mermaid
sequenceDiagram
    actor Client
    participant WC as WorkflowController
    participant WE as WorkflowEngineService
    participant GE as GuardEvaluator
    participant EO as EventOutboxService
    participant DB as PostgreSQL

    Client->>WC: POST /orders/{id}/workflow/transitions {triggerType, triggerCode}
    WC->>WE: fireTrigger(order, triggerType, triggerCode, actor, comment)

    WE->>DB: SELECT workflow_instance WHERE order_id = ?
    DB-->>WE: WorkflowInstance (current_state, version)

    Note over WE: Check: is current state terminal? → 409 if yes

    WE->>DB: SELECT transitions WHERE from_state = current AND ORDER BY sequence ASC
    DB-->>WE: List<WorkflowTransition>

    loop For each transition (in sequence order)
        WE->>GE: evaluate(guardExpression, {order context})
        GE-->>WE: true / false
        Note over WE: Stop at first match (triggerType + triggerCode + guard pass)
    end

    Note over WE: No match → throw ConflictException → 409

    WE->>DB: UPDATE workflow_instance SET current_state = toState
    WE->>DB: UPDATE order SET status = toState.code
    WE->>DB: INSERT workflow_transition_log
    WE->>EO: record("workflow.transitioned")
    WE->>EO: record("order.status_changed")
    EO->>DB: INSERT domain_event (x2)

    alt toState is MANUAL
        WE->>DB: INSERT task (status=UNASSIGNED, sla_due_at=now+24h)
        WE->>EO: record("task.created")
        EO->>DB: INSERT domain_event
    end

    Note over WE: runAutoProgress() — chains any immediate null-trigger transitions
    WE-->>WC: (done)
    WC-->>Client: 200 {updated workflow state}
```

---

### 7.3 Low-Value Order — Full Automated Lifecycle

A STANDARD order under $1,000 skips credit review; all transitions are EVENT-triggered.

```mermaid
sequenceDiagram
    actor Client
    participant API as REST API
    participant WE as WorkflowEngineService
    participant DB as PostgreSQL

    Client->>API: POST /orders {amount: 500}
    API->>DB: INSERT order (INITIALIZING)
    API->>WE: startInstance()
    WE->>DB: INSERT workflow_instance (CREATED)
    WE->>DB: UPDATE order status → CREATED
    API-->>Client: 201 {status: CREATED}

    Client->>API: POST /orders/{id}/workflow/transitions {event: order.submitted}
    WE->>DB: UPDATE instance → PAYMENT_PENDING
    WE->>DB: UPDATE order status → PAYMENT_PENDING
    Note over WE: runAutoProgress(): checks null-trigger transitions from PAYMENT_PENDING
    Note over WE: Guard {amount > 1000} → FALSE (500 ≤ 1000) → does NOT fire
    Note over WE: Waits for payment.captured event
    API-->>Client: 200 {status: PAYMENT_PENDING}

    Client->>API: POST /orders/{id}/workflow/transitions {event: payment.captured}
    WE->>DB: UPDATE instance → FULFILLMENT_QUEUED
    WE->>DB: UPDATE order status → FULFILLMENT_QUEUED
    API-->>Client: 200 {status: FULFILLMENT_QUEUED}

    Client->>API: POST /orders/{id}/workflow/transitions {event: shipment.dispatched}
    WE->>DB: UPDATE instance → SHIPPED
    WE->>DB: UPDATE order status → SHIPPED
    API-->>Client: 200 {status: SHIPPED}

    Client->>API: POST /orders/{id}/workflow/transitions {event: shipment.delivered}
    WE->>DB: UPDATE instance (completed_at = now) → DELIVERED [terminal]
    WE->>DB: UPDATE order status → DELIVERED
    API-->>Client: 200 {status: DELIVERED}
```

---

### 7.4 High-Value Order — Guard → Manual Review

A STANDARD order over $1,000: after `order.submitted`, auto-progress fires the guard-only transition to `CREDIT_REVIEW` and creates a task.

```mermaid
sequenceDiagram
    actor Client
    actor Reviewer
    participant API as REST API
    participant WE as WorkflowEngineService
    participant GE as GuardEvaluator
    participant DB as PostgreSQL

    Client->>API: POST /orders {amount: 5000}
    API->>DB: INSERT order (INITIALIZING)
    API->>WE: startInstance()
    WE->>DB: INSERT workflow_instance (CREATED)
    WE->>DB: UPDATE order status → CREATED
    API-->>Client: 201 {status: CREATED}

    Client->>API: POST /orders/{id}/workflow/transitions {event: order.submitted}
    WE->>DB: UPDATE instance → PAYMENT_PENDING
    WE->>DB: UPDATE order status → PAYMENT_PENDING

    Note over WE: runAutoProgress() — evaluate null-trigger transitions from PAYMENT_PENDING
    WE->>GE: evaluate({">": [totalAmount, 1000]}, {order.totalAmount=5000})
    GE-->>WE: TRUE

    WE->>DB: UPDATE instance → CREDIT_REVIEW (state_type = MANUAL)
    WE->>DB: UPDATE order status → CREDIT_REVIEW
    WE->>DB: INSERT task {status=UNASSIGNED, assigneeGroup=credit-team, sla_due_at=+24h}
    WE->>DB: INSERT domain_event (task.created)

    Note over WE: runAutoProgress() stops — current state is MANUAL
    API-->>Client: 200 {status: CREDIT_REVIEW}

    Reviewer->>API: POST /tasks/{taskId}/claim [If-Match: 0, X-User-Id: reviewer-1]
    API->>DB: UPDATE task {status=ASSIGNED, assignee_id=reviewer-1, claimed_at=now}
    API-->>Reviewer: 200 {version: 1}

    Reviewer->>API: POST /tasks/{taskId}/approve {comment: "looks fine"} [If-Match: 1]
    API->>DB: UPDATE task {status=APPROVED, decision=APPROVE, decision_by=reviewer-1}
    API->>WE: fireTaskDecision(task, APPROVE)
    WE->>DB: UPDATE instance → FULFILLMENT_QUEUED
    WE->>DB: UPDATE order status → FULFILLMENT_QUEUED
    WE->>DB: INSERT workflow_transition_log
    WE->>DB: INSERT domain_event (workflow.transitioned, order.status_changed, task.approved)
    API-->>Reviewer: 200 {status: APPROVED}
```

---

### 7.5 Task Claim and Approve

```mermaid
sequenceDiagram
    actor Worker as Ops Worker
    participant TC as TaskController
    participant TS as TaskService
    participant WE as WorkflowEngineService
    participant EO as EventOutboxService
    participant DB as PostgreSQL

    Worker->>TC: POST /tasks/{id}/claim [If-Match: 0, X-User-Id: worker-1]
    TC->>TS: claim(taskId, userId, expectedVersion=0)

    TS->>DB: SELECT task WHERE task_id = ?
    DB-->>TS: Task (version=0)

    TS->>TS: requireVersionMatch(0 == 0) ✓
    TS->>TS: requireOpen() ✓ (UNASSIGNED)

    TS->>DB: UPDATE task SET status=ASSIGNED, assignee_id=worker-1, claimed_at=now
    Note over DB: @Version bumps to 1 on flush
    TS->>EO: record("task.assigned")
    EO->>DB: INSERT domain_event

    TC-->>Worker: 200 {version: 1, status: ASSIGNED}

    Worker->>TC: POST /tasks/{id}/approve {comment: "approved"} [If-Match: 1, X-User-Id: worker-1]
    TC->>TS: approve(taskId, comment, expectedVersion=1, actor)

    TS->>DB: SELECT task WHERE task_id = ?
    DB-->>TS: Task (version=1)

    TS->>TS: requireVersionMatch(1 == 1) ✓
    TS->>TS: requireOpen() ✓ (ASSIGNED)

    TS->>DB: UPDATE task SET status=APPROVED, decision=APPROVE, completed_at=now
    Note over DB: @Version bumps to 2

    TS->>WE: fireTaskDecision(task, APPROVE, actor, comment)
    WE->>DB: SELECT transitions WHERE from_state = CREDIT_REVIEW AND trigger_type = TASK_APPROVED
    DB-->>WE: Transition → FULFILLMENT_QUEUED

    WE->>DB: UPDATE workflow_instance SET current_state = FULFILLMENT_QUEUED [optimistic lock check]
    WE->>DB: UPDATE order SET status = FULFILLMENT_QUEUED
    WE->>DB: INSERT workflow_transition_log
    WE->>EO: record("workflow.transitioned"), record("order.status_changed")
    EO->>DB: INSERT domain_event (x2)

    Note over WE: runAutoProgress() — checks FULFILLMENT_QUEUED for null-trigger transitions
    WE->>DB: (none found — waits for shipment.dispatched)

    TS->>EO: record("task.approved")
    EO->>DB: INSERT domain_event

    TC-->>Worker: 200 {version: 2, status: APPROVED}
```

---

### 7.6 Task Reject

```mermaid
sequenceDiagram
    actor Reviewer
    participant TC as TaskController
    participant TS as TaskService
    participant WE as WorkflowEngineService
    participant DB as PostgreSQL

    Reviewer->>TC: POST /tasks/{id}/reject {reason: "fraud risk"} [If-Match: 1]
    TC->>TS: reject(taskId, reason, expectedVersion=1, actor)

    TS->>DB: SELECT task (version=1)
    TS->>TS: requireVersionMatch(1==1) ✓
    TS->>TS: requireOpen() ✓

    TS->>DB: UPDATE task SET status=REJECTED, decision=REJECT, decision_reason="fraud risk"
    TS->>WE: fireTaskDecision(task, REJECT, actor, reason)

    WE->>DB: SELECT transitions WHERE from_state=CREDIT_REVIEW AND trigger_type=TASK_REJECTED
    DB-->>WE: Transition → CANCELLED

    WE->>DB: UPDATE workflow_instance SET current_state=CANCELLED, completed_at=now [terminal]
    WE->>DB: UPDATE order SET status=CANCELLED
    WE->>DB: INSERT workflow_transition_log
    WE->>DB: INSERT domain_event (workflow.transitioned, order.status_changed)

    TC-->>Reviewer: 200 {status: REJECTED}
```

---

### 7.7 Task Escalation (Manual)

A supervisor manually escalates a task before the SLA fires. Reason is required at the API level (returns 400 if blank).

```mermaid
sequenceDiagram
    actor Supervisor
    participant TC as TaskController
    participant TS as TaskService
    participant DB as PostgreSQL

    Supervisor->>TC: POST /tasks/{id}/escalate {reason: "needs senior review"} [If-Match: 1]
    TC->>TS: escalate(taskId, expectedVersion=1, actor, reason)

    TS->>TS: reason blank? → throw IllegalArgumentException → 400
    Note over TS: (reason is non-blank, continues)

    TS->>DB: SELECT task (version=1)
    TS->>TS: requireVersionMatch(1==1) ✓
    TS->>TS: requireOpen() ✓

    TS->>DB: UPDATE task SET status=ESCALATED, escalation_reason="needs senior review"
    TS->>DB: INSERT domain_event (task.escalated)

    TC-->>Supervisor: 200 {status: ESCALATED}

    Note over TC: Task remains actionable — ESCALATED is not a terminal status.
    Note over TC: A senior reviewer can still claim → approve/reject it.
```

---

### 7.8 SLA Sweep — Automatic Escalation

A `@Scheduled` job runs every 30 seconds (configurable via `oms.task.sla-sweep-interval-ms`). It escalates any non-terminal task whose `sla_due_at` has passed.

```mermaid
sequenceDiagram
    participant Scheduler as Spring Scheduler
    participant TS as TaskService
    participant EO as EventOutboxService
    participant DB as PostgreSQL

    Note over Scheduler: @Scheduled(fixedDelay = 30_000ms)
    Scheduler->>TS: sweepSlaBreaches()

    TS->>DB: SELECT tasks WHERE status IN (UNASSIGNED, ASSIGNED, IN_PROGRESS)\n         AND sla_due_at < now()
    DB-->>TS: List<Task> (0 or more overdue tasks)

    loop For each overdue task
        TS->>DB: UPDATE task SET\n  status = ESCALATED\n  assignee_id = null  (falls back to group queue)\n  priority = max(0, priority - 1)  (raised urgency)\n  escalation_reason = "SLA breach: not actioned by {sla_due_at}"

        TS->>EO: record("task.escalated", taskId, reason)
        EO->>DB: INSERT domain_event
    end

    Note over TS: Task is still actionable post-escalation.\n         No workflow transition fires here — the reviewer\n         must still approve or reject to exit the MANUAL state.
```

---

### 7.9 Publish Workflow Definition

```mermaid
sequenceDiagram
    actor Admin
    participant OTC as OrderTypeController
    participant OTS as OrderTypeService
    participant DB as PostgreSQL

    Admin->>OTC: PUT /order-types/{code}/workflow {name, states[], transitions[]}
    OTC->>OTS: publishWorkflow(orderTypeCode, command)

    OTS->>DB: SELECT order_type WHERE code = ?
    DB-->>OTS: OrderType

    Note over OTS: validateGraph(command):
    Note over OTS:   1. Exactly one initial state
    Note over OTS:   2. terminal_outcome set iff terminal=true
    Note over OTS:   3. BFS reachability from initial state
    Note over OTS:   4. Every non-terminal state has ≥1 outbound transition
    Note over OTS:   5. Every MANUAL state has TASK_APPROVED + TASK_REJECTED transitions
    Note over OTS: Any failure → throw IllegalArgumentException → 400

    OTS->>DB: SELECT MAX(version) WHERE order_type_code = ?
    DB-->>OTS: currentMax (e.g. 2)

    OTS->>DB: INSERT workflow_definition (version = currentMax + 1 = 3)
    DB-->>OTS: WorkflowDefinition (new id)

    loop For each state spec
        OTS->>DB: INSERT workflow_state
    end

    loop For each transition spec
        OTS->>DB: INSERT workflow_transition
    end

    OTS->>DB: UPDATE order_type SET workflow_definition_id = <new definition id>
    Note over DB: All above in one @Transactional — atomic publish

    OTS-->>OTC: WorkflowDefinition
    OTC-->>Admin: 201 Location: /workflow-definitions/{newId}

    Note over Admin: Existing workflow_instance rows keep their\n         previously pinned workflow_definition_id — unaffected.
```

---

### 7.10 Optimistic Lock Conflict

Two users attempt to update the same order or task concurrently. The second write is rejected.

```mermaid
sequenceDiagram
    actor UserA
    actor UserB
    participant API as REST API
    participant DB as PostgreSQL

    UserA->>API: GET /orders/{id}
    API->>DB: SELECT order (version=3)
    API-->>UserA: {version: 3}

    UserB->>API: GET /orders/{id}
    API->>DB: SELECT order (version=3)
    API-->>UserB: {version: 3}

    UserA->>API: PATCH /orders/{id} [If-Match: 3] {customerRef: "A-new"}
    API->>API: requireVersionMatch(stored=3, expected=3) ✓
    API->>DB: UPDATE order SET customer_ref="A-new", version=4 WHERE version=3
    DB-->>API: 1 row updated
    API-->>UserA: 200 {version: 4}

    UserB->>API: PATCH /orders/{id} [If-Match: 3] {customerRef: "B-new"}
    API->>API: requireVersionMatch(stored=4, expected=3) ✗
    API-->>UserB: 409 Conflict "Version mismatch on order {id}: expected 3 but was 4"

    Note over UserB: UI shows "This order changed since you loaded it"\n         and auto-refreshes the record (UI-SPEC.md §5).
```

---

### 7.11 Transactional Outbox — Event Delivery

Domain events are written to the `domain_event` table in the **same DB transaction** as the state change, eliminating dual-write hazards. A separate publisher process delivers them to the message bus.

```mermaid
sequenceDiagram
    participant WE as WorkflowEngineService
    participant EO as EventOutboxService
    participant DB as PostgreSQL
    participant Publisher as Event Publisher (CDC / poller)
    participant Bus as Message Bus

    Note over WE,DB: Within a single @Transactional
    WE->>DB: UPDATE workflow_instance
    WE->>DB: UPDATE order
    WE->>DB: INSERT workflow_transition_log
    WE->>EO: record("workflow.transitioned", ...)
    EO->>DB: INSERT domain_event {published_at = NULL}
    WE->>EO: record("order.status_changed", ...)
    EO->>DB: INSERT domain_event {published_at = NULL}
    Note over WE,DB: COMMIT — both state and events land atomically

    loop Poll or CDC (Debezium)
        Publisher->>DB: SELECT * FROM domain_event WHERE published_at IS NULL
        DB-->>Publisher: List<DomainEvent>
        loop For each event
            Publisher->>Bus: publish(event)
            Bus-->>Publisher: ACK
            Publisher->>DB: UPDATE domain_event SET published_at = now()
        end
    end

    Note over Publisher: At-least-once delivery — consumers must dedupe on event_id.
```

---

### 7.12 Order Schema Extension (PATCH)

Adding a new field to an existing order type — no DDL, no deploy required.

```mermaid
sequenceDiagram
    actor Admin
    participant OTC as OrderTypeController
    participant OTS as OrderTypeService
    participant DB as PostgreSQL

    Admin->>OTC: PATCH /order-types/STANDARD\n{attributeSchema: {type:object, properties:{giftMessage:{...}}}}
    OTC->>OTS: updateSchema("STANDARD", newAttributeSchema, null)

    OTS->>DB: SELECT order_type WHERE code = "STANDARD"
    DB-->>OTS: OrderType

    OTS->>OTS: assertValidJson(newAttributeSchema) ✓

    OTS->>DB: UPDATE order_type SET attribute_schema = <new JSON>
    DB-->>OTS: OrderType (updated)

    OTS-->>OTC: OrderType
    OTC-->>Admin: 200 {code: STANDARD, attributeSchema: {...giftMessage...}}

    Note over DB: Existing order.attributes rows are NOT re-validated.\n         New schema is enforced on the NEXT write to any order\n         of type STANDARD (UI-SPEC.md §4.2).
```

---

### 7.13 Tenant Resolution

Runs in front of every request in §7.1–§7.12 above; omitted from those diagrams for brevity. Full detail in §13.

```mermaid
sequenceDiagram
    actor Client
    participant TF as TenantFilter
    participant TR as TenantRepository
    participant TC as TenantContext (ThreadLocal)
    participant Ctrl as Any Controller
    participant Hib as Hibernate (SpringTenantIdentifierResolver)
    participant DB as PostgreSQL

    Client->>TF: any request, header X-Tenant-Id: acme
    alt header missing or blank
        TF-->>Client: 400 Bad Request
    end
    TF->>TR: findByTenantIdAndActiveTrue("acme")
    TR->>DB: SELECT tenant WHERE tenant_id = 'acme' AND is_active
    alt no matching row
        DB-->>TR: empty
        TF-->>Client: 404 Not Found
    end
    DB-->>TR: Tenant
    TF->>TC: TenantContext.set("acme")

    TF->>Ctrl: chain.doFilter() — request proceeds
    Note over Ctrl: Controller/service code is unchanged —\n         no tenant-aware logic anywhere here.
    Ctrl->>Hib: any query against a @TenantId entity\n         (Order, Task, OrderType, ...)
    Hib->>TC: resolveCurrentTenantIdentifier()
    TC-->>Hib: "acme"
    Hib->>DB: ... WHERE tenant_id = 'acme' AND ...
    DB-->>Ctrl: rows scoped to acme only

    Ctrl-->>TF: response
    TF->>TC: TenantContext.clear()  (in finally — always runs)
    TF-->>Client: response
```

`TenantFilter` is registered at `Ordered.HIGHEST_PRECEDENCE` so it runs before Spring Security (if ever added) and every controller. The two `@Scheduled` jobs (SLA sweep, outbox publisher, §10) have no request to run this filter on, so they call `TenantContext.runAs(tenantId, ...)` directly, once per active tenant — see §13.

---

## 8. Workflow Engine Deep Dive

### State Types

| Type | Engine behavior | Task created? | Exit mechanism |
|---|---|---|---|
| `AUTOMATIC` | Immediately evaluates outbound transitions in `sequence` order; fires the first one whose trigger matches and guard passes. | No | External event or null-trigger transition. |
| `MANUAL` | Same evaluation logic as AUTOMATIC, but first creates a `task` row on entry. | **Yes** | Only via `TASK_APPROVED` or `TASK_REJECTED` trigger from a task decision. |
| `WAIT` | Identical evaluation to AUTOMATIC. The type is a monitoring hint — alerting systems can set different SLAs for WAIT states. | No | External event or null-trigger transition. |

### Transition Evaluation Order

For a given `from_state`, the engine selects among outbound transitions by:

1. Match `trigger_type` and `trigger_code` (or `trigger_code IS NULL` for auto-fire).
2. Evaluate `guard_expression` via `GuardEvaluator` against the order context.
3. Fire the **first** transition (lowest `sequence` value) where both conditions pass.

This is how multi-branch states work (e.g., `PAYMENT_PENDING` has a guard-only `CREDIT_REVIEW` branch at sequence=0 and a `payment.captured` branch at sequence=1).

### Workflow Versioning

```
order_type.workflow_definition_id  ←──── the ONLY "active version" pointer
       │
       ▼
workflow_definition (version N)
       │
       ├── workflow_state (CREATED, PAYMENT_PENDING, ...)
       └── workflow_transition (CREATED→PAYMENT_PENDING on order.submitted, ...)

workflow_instance (per order)
       └── workflow_definition_id  ←── pinned at order creation time, NEVER changes
```

Publishing a new workflow version (`PUT /order-types/{code}/workflow`) inserts a new `workflow_definition` row and atomically updates `order_type.workflow_definition_id`. All existing `workflow_instance` rows remain pinned to their original version.

### Standard Workflow State Diagram

```mermaid
stateDiagram-v2
    [*] --> CREATED : order created
    CREATED --> PAYMENT_PENDING : EVENT order.submitted

    state check_amount <<choice>>
    PAYMENT_PENDING --> check_amount : auto-evaluate on entry
    check_amount --> CREDIT_REVIEW : guard (totalAmount > 1000)
    check_amount --> FULFILLMENT_QUEUED : EVENT payment.captured [totalAmount ≤ 1000]

    CREDIT_REVIEW --> FULFILLMENT_QUEUED : TASK_APPROVED
    CREDIT_REVIEW --> CANCELLED : TASK_REJECTED

    FULFILLMENT_QUEUED --> SHIPPED : EVENT shipment.dispatched
    SHIPPED --> DELIVERED : EVENT shipment.delivered
    DELIVERED --> [*]
    CANCELLED --> [*]

    note right of CREDIT_REVIEW
        state_type = MANUAL
        Creates a task on entry.
        Assignee group: credit-team
    end note
```

---

## 9. Human Task Queue

### Task Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> UNASSIGNED : WorkflowEngine enters MANUAL state
    UNASSIGNED --> ASSIGNED : claim (pull) or assign (push)
    ASSIGNED --> IN_PROGRESS : (optional — set by worker)
    IN_PROGRESS --> ASSIGNED : (re-assign)

    UNASSIGNED --> ESCALATED : SLA breach (sweepSlaBreaches) or manual escalate
    ASSIGNED --> ESCALATED : SLA breach or manual escalate
    IN_PROGRESS --> ESCALATED : SLA breach or manual escalate
    ESCALATED --> ASSIGNED : re-claim after escalation

    ASSIGNED --> APPROVED : approve → fires TASK_APPROVED in workflow
    ASSIGNED --> REJECTED : reject → fires TASK_REJECTED in workflow
    ESCALATED --> APPROVED : approve (still actionable)
    ESCALATED --> REJECTED : reject (still actionable)

    APPROVED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
```

### Task Fields Reference

| Field | Set when | Notes |
|---|---|---|
| `assignee_group` | Task created | Copied from `workflow_state.default_assignee_group`. Supports pull (anyone in the group claims) or push (lead assigns directly). |
| `assignee_id` | `claim` or `assign` | Null for group-queued tasks. |
| `sla_due_at` | Task created | `now() + oms.task.default-sla-hours` (default 24h, configurable). |
| `decision` / `decision_reason` | `approve` / `reject` | Stored for audit; `decision_reason` appears in Order Detail's workflow history. |
| `escalation_reason` | `escalate` or SLA sweep | Distinct from `decision_reason`. Required for manual escalation (400 if blank). System fills it automatically on SLA breach. |
| `version` | Every mutation | Independent optimistic lock from `order.version`. |

---

## 10. Event System

### Emitted Events

| Event | Aggregate | Payload fields |
|---|---|---|
| `order.created` | ORDER | `orderId`, `orderTypeCode`, `occurredAt`, `triggeredBy` |
| `order.updated` | ORDER | `orderId`, `occurredAt`, `triggeredBy` |
| `order.status_changed` | ORDER | `orderId`, `fromStatus`, `toStatus`, `occurredAt`, `triggeredBy` |
| `workflow.transitioned` | WORKFLOW_INSTANCE | `orderId`, `orderTypeCode`, `fromState`, `toState`, `occurredAt`, `triggeredBy` |
| `task.created` | TASK | `taskId`, `orderId`, `taskType`, `assigneeGroup`, `occurredAt` |
| `task.assigned` | TASK | `taskId`, `assigneeId`, `occurredAt` |
| `task.approved` | TASK | `taskId`, `occurredAt`, `triggeredBy` |
| `task.rejected` | TASK | `taskId`, `occurredAt`, `triggeredBy` |
| `task.escalated` | TASK | `taskId`, `reason`, `occurredAt` |

### Outbox Pattern

- Every `record()` call in `EventOutboxService` writes a `domain_event` row with `published_at = NULL`.
- The call **must** happen inside the caller's existing `@Transactional` scope — the outbox row commits atomically with the state change.
- A separate publisher (poller or CDC via Debezium) queries `WHERE published_at IS NULL`, delivers to the broker, then sets `published_at = now()`.
- Consumers must dedupe on `event_id` because at-least-once delivery can produce redeliveries on publisher crash.

---

## 11. Frontend Architecture

### Page Inventory

| Route | Page | Console | Key API calls |
|---|---|---|---|
| `/ops/orders` | OrderListPage | Ops | `GET /orders`, `GET /order-types/status-taxonomy` |
| `/ops/orders/new` | OrderCreatePage | Ops | `GET /order-types`, `GET /order-types/{code}/schema`, `POST /orders` |
| `/ops/orders/:id` | OrderDetailPage | Ops | `GET /orders/{id}`, `GET /orders/{id}/workflow`, `POST /orders/{id}/workflow/transitions` |
| `/ops/tasks` | TaskQueuePage | Ops | `GET /tasks` |
| `/ops/tasks/:id` | TaskDetailPage | Ops | `GET /tasks/{id}`, `POST /tasks/{id}/claim`, `POST /tasks/{id}/approve`, `POST /tasks/{id}/reject`, `POST /tasks/{id}/escalate`, `POST /tasks/{id}/comments` |
| `/track/:orderId` | OrderTrackingPage | Customer | `GET /orders/{id}`, `GET /orders/{id}/workflow` |
| `/admin/order-types` | OrderTypeListPage | Admin | `GET /order-types` |
| `/admin/order-types/new` | OrderTypeEditorPage | Admin | `POST /order-types`, `PUT /order-types/{code}/workflow` |
| `/admin/order-types/:code/workflow` | WorkflowDesignerPage | Admin | `GET /workflow-definitions/{id}`, `PUT /order-types/{code}/workflow` |

### Component Hierarchy

```
App (React Router)
├── OpsAdminLayout
│   ├── OrderListPage
│   │   ├── StatusBadge
│   │   ├── Pagination
│   │   └── useStatusTaxonomy (hook → GET /order-types/status-taxonomy)
│   ├── OrderCreatePage
│   │   └── DynamicSchemaForm   (renders inputs from JSON Schema)
│   ├── OrderDetailPage
│   │   ├── StatusBadge
│   │   ├── ConflictBanner      (409 handling)
│   │   └── DynamicSchemaForm   (attributes read-only view)
│   ├── TaskQueuePage
│   │   └── SlaBadge            (green/amber/red by SLA remaining)
│   ├── TaskDetailPage
│   │   ├── SlaBadge
│   │   └── ConflictBanner
│   ├── OrderTypeListPage
│   ├── OrderTypeEditorPage
│   │   └── SchemaBuilder       (field-by-field JSON Schema editor)
│   └── WorkflowDesignerPage
│       └── workflowGraph.ts    (canvas state: nodes + edges)
└── CustomerLayout
    └── OrderTrackingPage
        └── StatusBadge (customer-facing label, customer-visible states only)
```

### API Client Layer

All fetch calls go through `web/src/lib/api.ts` (a thin fetch wrapper):
- Base URL resolved by Vite proxy (`/orders` → `http://localhost:8080/orders`).
- `X-User-Id` injected from `actingUser` context (set in `OpsAdminLayout`).
- `X-Tenant-Id` injected the same way, from `getActingTenant()`/`setActingTenant()` (`localStorage`, default `'default'`) — there's no tenant-switcher UI yet, so this always sends whatever's in `localStorage` (§13).
- `If-Match` headers manually threaded through action calls.
- `409 Conflict` surfaces as a thrown error the caller catches; `ConflictBanner` renders the message.

### Dynamic Schema Form

`DynamicSchemaForm` reads `order_type.attribute_schema` (JSON Schema) and renders:

| JSON Schema | Widget |
|---|---|
| `type: string` | `<input type="text">` |
| `type: string, format: date` | `<input type="date">` |
| `type: number` / `integer` | `<input type="number">` |
| `type: boolean` | `<input type="checkbox">` |
| `enum: [...]` | `<select>` |

`x-show-in-task: true` vendor extension causes the field to appear in Task Detail's context panel (§2.4 of UI-SPEC). `x-customer-visible: true` is stored but not yet consumed in any UI (reserved for a future customer portal attributes screen).

---

## 12. Concurrency & Consistency

### Optimistic Locking Summary

| Resource | Lock column | Updated by |
|---|---|---|
| `order` | `version` | `OrderService.updateOrder`, `WorkflowEngineService.applyTransition` |
| `workflow_instance` | `version` | `WorkflowEngineService.applyTransition` |
| `task` | `version` | `TaskService.claim`, `.assign`, `.approve`, `.reject`, `.escalate` |
| `order_line` | `version` | `OrderService.updateLine` |

All three locks (`order.version`, `workflow_instance.version`, `task.version`) may be touched in a single transaction (e.g., task approve updates all three). They are independent — a `409` on a task action is always "this task changed", not "this order changed."

### Status Consistency Invariant

`order.status` is written **only** by `WorkflowEngineService.applyTransition` (and `startInstance`). `PATCH /orders/{id}` never touches `status`. This means `order.status` always mirrors `workflow_instance.current_state.code` and can be trusted as a fast read-optimized projection without joining workflow tables.

### Workflow Definition Immutability

`workflow_definition` rows are immutable once published (no `UPDATE` path exists). "Editing" a workflow always produces a new row with a new `version`. The `order_type.workflow_definition_id` FK is the only mutable pointer; repointing it atomically in one transaction is the publish operation.

### In-Flight Order Protection

```
Time 0: order created → workflow_instance.workflow_definition_id = V1
Time 1: admin publishes V2 → order_type.workflow_definition_id = V2
Time 2: new orders pick up V2; existing order still runs on V1 (pinned)
```

---

## 13. Multi-Tenancy

### Design

Shared schema, discriminator column — not schema-per-tenant or database-per-tenant (see the Key Schema Decisions row in §4 for why). Every tenant-owned table carries a `tenant_id VARCHAR(64)` column: `order_type`, `order`, `order_line`, `workflow_definition`, `workflow_state`, `workflow_transition`, `workflow_instance`, `workflow_transition_log`, `task`, `task_comment`, `domain_event`. A separate `tenant` table (`tenant_id` PK, `name`, `is_active`) is the registry — it is not itself tenant-scoped.

### Package layout

```
com.oms.domain.tenant.Tenant          JPA entity for the registry table
com.oms.repository.TenantRepository   findAllByActiveTrue(), findByTenantIdAndActiveTrue(...)
com.oms.tenant.TenantContext           ThreadLocal<String> holder; set/get/clear/runAs
com.oms.tenant.TenantFilter            servlet Filter, Ordered.HIGHEST_PRECEDENCE
com.oms.tenant.SpringTenantIdentifierResolver   Hibernate CurrentTenantIdentifierResolver<String>
com.oms.tenant.TenantHibernateConfig   registers the resolver via HibernatePropertiesCustomizer
```

### Enforcement: Hibernate `@TenantId`, not application code

Each of the 11 entities above has a field:

```java
@TenantId
@Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
private String tenantId;
```

`@TenantId` is a Hibernate 6 ORM feature (not a JPA standard annotation). Once any entity in the persistence unit uses it and a `hibernate.tenant_identifier_resolver` is configured, Hibernate:
- appends `tenant_id = ?` to the generated SQL for **every** query against that entity — derived queries, `@Query`, and `Specification`-based dynamic queries (`OrderRepository.hasStatusIn(...)`, `TaskRepository.hasAssigneeGroup(...)`, etc.) all pick it up automatically, with no change to any repository or service method;
- populates `tenant_id` on `INSERT` from the same resolver;
- rejects mutation of `tenant_id` after insert (`updatable = false`).

This is why `OrderService`, `TaskService`, `OrderTypeService`, and `WorkflowEngineService` (§5) needed **zero** code changes to become tenant-aware.

### Resolving "the current tenant"

`SpringTenantIdentifierResolver.resolveCurrentTenantIdentifier()` returns `TenantContext.get()`. Two callers populate that `ThreadLocal`:

1. **`TenantFilter`**, for every HTTP request (§7.13) — reads `X-Tenant-Id`, returns `400` if missing/blank, `404` if it doesn't match an active row in `tenant`, otherwise sets the context for the rest of the request and clears it in a `finally` block.
2. **`TenantContext.runAs(tenantId, action)`**, for code that runs outside any request — see Scheduled jobs below.

If Hibernate ever needs to resolve a tenant identifier with neither of the above having run — e.g. Spring Data's query-method validation at application startup, which opens a throwaway `Session` before any request exists — the resolver falls back to a sentinel string (`"__no_tenant__"`) instead of throwing. This keeps startup from failing, and because the sentinel matches no real `tenant_id` value, any *production* code path that somehow runs without a properly-set context fails safe (queries return nothing) rather than leaking another tenant's data by silently defaulting to a real tenant.

### Scheduled jobs: the one gap `@TenantId` doesn't cover

`TaskService.sweepSlaBreaches()` (§9, SLA escalation) and `DomainEventPublisher.publishPending()` (§10, outbox drain) are both `@Scheduled` — they run on a timer thread, not inside an HTTP request, so `TenantFilter` never touches them. Both now do:

```java
for (Tenant t : tenantRepository.findAllByActiveTrue()) {
    TenantContext.runAs(t.getTenantId(), () -> /* unchanged per-tenant body */);
}
```

The per-tenant body in each is exactly what it was before multi-tenancy — only the outer loop and the `TenantContext.runAs` wrapping are new. Both methods keep a single `@Transactional` boundary spanning the whole loop (all tenants), trading per-tenant transaction isolation for simplicity; a failure on one tenant's batch rolls back the whole sweep/publish cycle rather than just that tenant's slice. Given these jobs are idempotent and re-run every 30s/5s respectively, that's an acceptable trade.

### Referential integrity across tenants

Two natural-key uniqueness constraints that used to be global are now composite on `(tenant_id, ...)`:

| Table | Old constraint | New constraint |
|---|---|---|
| `order_type` | `UNIQUE (code)` | `UNIQUE (tenant_id, code)` |
| `workflow_definition` | `UNIQUE (order_type_code, version)` | `UNIQUE (tenant_id, order_type_code, version)` |

The one FK that pointed at a natural key — `orders.order_type_code REFERENCES order_type (code)` — had to be widened to a composite FK, since a single-column FK to `code` would let an order resolve to *any* tenant's order type sharing that code:

```sql
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_order_type FOREIGN KEY (tenant_id, order_type_code)
        REFERENCES order_type (tenant_id, code);
```

Every other FK in the schema (`workflow_state.workflow_definition_id`, `task.order_id`, etc.) keys off a globally-unique UUID primary key, so none of them needed to change — a UUID row only ever belongs to one tenant, by construction. `orders.order_number` deliberately stays globally unique (one shared `order_number_seq` across all tenants); there's no leak risk in a value nothing else joins against by natural key.

### Migration (`V4__add_multi_tenancy.sql`)

In order: create `tenant` and seed a `default` row → add `tenant_id` to all 11 tables with `DEFAULT 'default'` (backfills every existing row, since the column is `NOT NULL`) → drop the default (new rows must always supply a real value via `@TenantId`) → add FKs from each table's `tenant_id` to `tenant` → drop and recreate the `order_type`/`workflow_definition` unique constraints and the `orders → order_type` FK in the dependency order described above (the FK has to drop *before* its target unique constraint is replaced, or Postgres refuses with "other objects depend on it") → rebuild the low-cardinality composite indexes (`idx_orders_status`, `idx_orders_order_type_code`, `idx_orders_customer_ref`, `idx_task_status_assignee_group`, `idx_domain_event_unpublished`) with `tenant_id` as the leading column, since every one of these queries now also filters on `tenant_id` and would otherwise lose selectivity.

### Frontend

`web/src/lib/api.ts` sends `X-Tenant-Id` on every request (§11, API Client Layer) from `getActingTenant()`, mirroring `getActingUser()` exactly — same `localStorage` key pattern, same default-on-missing behavior (`'default'` vs `'ops-user'`). There is currently no UI control to change it; switching tenants in the browser means calling `localStorage.setItem('oms.actingTenant', '<id>')` directly (GUIDE.md §4.4).

### Verification

- `OrderWorkflowIT` sends `X-Tenant-Id: default` via a `ClientHttpRequestInterceptor` added to its `TestRestTemplate`, and additionally sets `TenantContext` directly in `@BeforeEach`/cleared in `@AfterEach` — needed because a couple of assertions call `DomainEventRepository` directly from the test thread, bypassing `TenantFilter` entirely (HTTP calls go through the filter on a *server* thread; direct repository calls from the test run on the *test's own* thread, which never goes through the filter).
- Manual check: two tenants (`default`, and a freshly-inserted `acme`) can each register an `order_type` with `code = "STANDARD"` without conflict, and `GET /order-types` under each tenant returns only that tenant's rows — see GUIDE.md §4.3 for the exact commands.

No in-flight order can be silently affected by a workflow edit. Orders complete on the version they started on, always.
