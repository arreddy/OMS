-- Multi-tenancy: shared schema, tenant_id discriminator column on every
-- tenant-owned table, enforced at the ORM layer by Hibernate's @TenantId
-- (see com.oms.tenant package). Existing rows backfill to the 'default'
-- tenant; new rows must always supply a real tenant_id going forward.

CREATE TABLE tenant (
    tenant_id  VARCHAR(64) PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO tenant (tenant_id, name) VALUES ('default', 'Default Tenant');

ALTER TABLE order_type              ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_definition     ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_state          ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_transition     ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE orders                  ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE order_line              ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_instance       ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE workflow_transition_log ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE task                    ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE task_comment            ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE domain_event            ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- Going forward tenant_id is always supplied explicitly by Hibernate
-- (@TenantId populates it from TenantContext) — no implicit default.
ALTER TABLE order_type              ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE workflow_definition     ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE workflow_state          ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE workflow_transition     ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE orders                  ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE order_line              ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE workflow_instance       ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE workflow_transition_log ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE task                    ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE task_comment            ALTER COLUMN tenant_id DROP DEFAULT;
ALTER TABLE domain_event            ALTER COLUMN tenant_id DROP DEFAULT;

ALTER TABLE order_type              ADD CONSTRAINT fk_order_type_tenant              FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE workflow_definition     ADD CONSTRAINT fk_workflow_definition_tenant     FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE workflow_state          ADD CONSTRAINT fk_workflow_state_tenant          FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE workflow_transition     ADD CONSTRAINT fk_workflow_transition_tenant     FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE orders                  ADD CONSTRAINT fk_orders_tenant                  FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE order_line              ADD CONSTRAINT fk_order_line_tenant              FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE workflow_instance       ADD CONSTRAINT fk_workflow_instance_tenant       FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE workflow_transition_log ADD CONSTRAINT fk_workflow_transition_log_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE task                    ADD CONSTRAINT fk_task_tenant                    FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE task_comment            ADD CONSTRAINT fk_task_comment_tenant           FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);
ALTER TABLE domain_event            ADD CONSTRAINT fk_domain_event_tenant           FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id);

-- Natural-key uniqueness was global; each tenant now owns its own namespace.
-- orders_order_type_code_fkey depends on order_type_code_key, so it must be
-- dropped first before that unique constraint can be replaced.
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_order_type_code_fkey;

ALTER TABLE order_type
    DROP CONSTRAINT IF EXISTS order_type_code_key,
    ADD CONSTRAINT uq_order_type_tenant_code UNIQUE (tenant_id, code);

ALTER TABLE workflow_definition
    DROP CONSTRAINT IF EXISTS workflow_definition_order_type_code_version_key,
    ADD CONSTRAINT uq_workflow_definition_tenant_order_type_version UNIQUE (tenant_id, order_type_code, version);

-- orders.order_type_code referenced order_type(code) directly; now that code
-- is only unique per tenant, the FK must be widened to (tenant_id, code) so
-- an order can never resolve to another tenant's order type.
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_order_type FOREIGN KEY (tenant_id, order_type_code)
        REFERENCES order_type (tenant_id, code);

-- order_number stays globally unique — it is generated from a single shared
-- sequence (order_number_seq), so no tenant ever collides with another's.

-- Rebuild composite indexes on low-cardinality columns with tenant_id
-- leading, since every query now also filters on tenant_id.
DROP INDEX idx_orders_status;
CREATE INDEX idx_orders_status ON orders (tenant_id, status);

DROP INDEX idx_orders_order_type_code;
CREATE INDEX idx_orders_order_type_code ON orders (tenant_id, order_type_code);

DROP INDEX idx_orders_customer_ref;
CREATE INDEX idx_orders_customer_ref ON orders (tenant_id, customer_ref);

DROP INDEX idx_task_status_assignee_group;
CREATE INDEX idx_task_status_assignee_group ON task (tenant_id, status, assignee_group);

DROP INDEX idx_domain_event_unpublished;
CREATE INDEX idx_domain_event_unpublished ON domain_event (tenant_id, occurred_at) WHERE published_at IS NULL;
