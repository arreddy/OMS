-- Core schema per SPEC.md. Note: the order header table is named "orders"
-- (not "order") because ORDER is a reserved SQL keyword.

CREATE TABLE order_type (
    order_type_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                    VARCHAR(50) NOT NULL UNIQUE,
    name                    VARCHAR(100) NOT NULL,
    attribute_schema        JSONB NOT NULL,
    line_attribute_schema   JSONB NOT NULL,
    workflow_definition_id  UUID,
    is_active               BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE workflow_definition (
    workflow_definition_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_type_code         VARCHAR(50) NOT NULL,
    version                 INT NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    published_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_type_code, version)
);

ALTER TABLE order_type
    ADD CONSTRAINT fk_order_type_workflow_definition
    FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definition (workflow_definition_id);

CREATE TABLE workflow_state (
    state_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_definition_id  UUID NOT NULL REFERENCES workflow_definition (workflow_definition_id),
    code                    VARCHAR(50) NOT NULL,
    state_type              VARCHAR(20) NOT NULL CHECK (state_type IN ('AUTOMATIC', 'MANUAL', 'WAIT')),
    is_initial              BOOLEAN NOT NULL DEFAULT FALSE,
    is_terminal             BOOLEAN NOT NULL DEFAULT FALSE,
    -- Default assignee_group for tasks created on entry to a MANUAL state (SPEC.md §5.3
    -- step 1 references "assignee_group from state config" but the original table didn't
    -- carry it; added here to close that gap).
    default_assignee_group  VARCHAR(100),
    UNIQUE (workflow_definition_id, code)
);

CREATE TABLE workflow_transition (
    transition_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_definition_id  UUID NOT NULL REFERENCES workflow_definition (workflow_definition_id),
    from_state_id           UUID NOT NULL REFERENCES workflow_state (state_id),
    to_state_id              UUID NOT NULL REFERENCES workflow_state (state_id),
    sequence                INT NOT NULL DEFAULT 0,
    trigger_type             VARCHAR(20) NOT NULL CHECK (trigger_type IN
        ('EVENT', 'API_ACTION', 'TASK_APPROVED', 'TASK_REJECTED', 'TIMER')),
    trigger_code             VARCHAR(50),
    guard_expression          TEXT,
    side_effect               VARCHAR(100)
);

CREATE INDEX idx_workflow_transition_from_state ON workflow_transition (from_state_id, sequence);

CREATE TABLE orders (
    order_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number    VARCHAR(40) NOT NULL UNIQUE,
    order_type_code VARCHAR(50) NOT NULL REFERENCES order_type (code),
    status          VARCHAR(50) NOT NULL,
    customer_ref    VARCHAR(100),
    currency        CHAR(3) NOT NULL,
    total_amount    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    attributes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(100),
    updated_by      VARCHAR(100)
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_order_type_code ON orders (order_type_code);
CREATE INDEX idx_orders_customer_ref ON orders (customer_ref);

-- Default order_number generator: SPEC.md leaves the numbering format owned by
-- order_type but doesn't define a counter; this sequence backs the default
-- "{ORDER_TYPE_CODE}-{seq}" scheme used by OrderService until a real per-type
-- format is needed.
CREATE SEQUENCE order_number_seq START WITH 100000;

CREATE TABLE order_line (
    line_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders (order_id),
    line_number  INT NOT NULL,
    item_ref     VARCHAR(100) NOT NULL,
    quantity     NUMERIC(12, 3) NOT NULL,
    unit_price   NUMERIC(18, 2) NOT NULL,
    line_total   NUMERIC(18, 2) NOT NULL,
    status       VARCHAR(50) NOT NULL,
    attributes   JSONB NOT NULL DEFAULT '{}'::jsonb,
    version      BIGINT NOT NULL DEFAULT 0,
    UNIQUE (order_id, line_number)
);

CREATE TABLE workflow_instance (
    instance_id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id                UUID NOT NULL UNIQUE REFERENCES orders (order_id),
    workflow_definition_id  UUID NOT NULL REFERENCES workflow_definition (workflow_definition_id),
    current_state_id        UUID NOT NULL REFERENCES workflow_state (state_id),
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    version                 BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE workflow_transition_log (
    log_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    instance_id      UUID NOT NULL REFERENCES workflow_instance (instance_id),
    from_state_code  VARCHAR(50),
    to_state_code    VARCHAR(50) NOT NULL,
    trigger_type     VARCHAR(20),
    trigger_code     VARCHAR(50),
    triggered_by     VARCHAR(100) NOT NULL,
    comment          TEXT,
    occurred_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_workflow_transition_log_instance ON workflow_transition_log (instance_id, occurred_at);

CREATE TABLE task (
    task_id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID NOT NULL REFERENCES orders (order_id),
    workflow_instance_id  UUID NOT NULL REFERENCES workflow_instance (instance_id),
    state_id              UUID NOT NULL REFERENCES workflow_state (state_id),
    task_type             VARCHAR(50) NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN
        ('UNASSIGNED', 'ASSIGNED', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'ESCALATED', 'CANCELLED')),
    assignee_id           VARCHAR(100),
    assignee_group        VARCHAR(100),
    priority              SMALLINT NOT NULL DEFAULT 5,
    sla_due_at            TIMESTAMPTZ,
    decision              VARCHAR(10) CHECK (decision IN ('APPROVE', 'REJECT')),
    decision_reason       TEXT,
    decision_by           VARCHAR(100),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    version               BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_task_status_assignee_group ON task (status, assignee_group);
CREATE INDEX idx_task_workflow_instance ON task (workflow_instance_id);

CREATE TABLE task_comment (
    comment_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id     UUID NOT NULL REFERENCES task (task_id),
    author_id   VARCHAR(100) NOT NULL,
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE domain_event (
    event_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,
    aggregate_type  VARCHAR(20) NOT NULL CHECK (aggregate_type IN ('ORDER', 'WORKFLOW_INSTANCE', 'TASK')),
    aggregate_id    UUID NOT NULL,
    payload         JSONB NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX idx_domain_event_unpublished ON domain_event (occurred_at) WHERE published_at IS NULL;
