-- Fields the UI spec (order-management-system-ui-spec.md) needs that the
-- original schema didn't carry: customer-facing projection of workflow
-- states, terminal outcome polarity for badge coloring, designer canvas
-- layout, and an escalation reason on task.

ALTER TABLE workflow_state
    ADD COLUMN is_customer_visible    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN customer_facing_label  VARCHAR(200),
    ADD COLUMN terminal_outcome       VARCHAR(10) CHECK (terminal_outcome IN ('SUCCESS', 'FAILURE')),
    ADD COLUMN canvas_x               NUMERIC(10, 2),
    ADD COLUMN canvas_y               NUMERIC(10, 2);

ALTER TABLE task
    ADD COLUMN escalation_reason TEXT;

-- Backfill the STANDARD workflow seeded in V2: which states are
-- customer-visible (CREDIT_REVIEW stays internal-only, per the UI spec's
-- example), their customer-facing labels, and terminal outcome polarity.
DO $$
DECLARE
    v_def_id UUID;
BEGIN
    SELECT workflow_definition_id INTO v_def_id
    FROM workflow_definition WHERE order_type_code = 'STANDARD' AND version = 1;

    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Order received'
        WHERE workflow_definition_id = v_def_id AND code = 'CREATED';
    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Processing payment'
        WHERE workflow_definition_id = v_def_id AND code = 'PAYMENT_PENDING';
    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Preparing your order'
        WHERE workflow_definition_id = v_def_id AND code = 'FULFILLMENT_QUEUED';
    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Shipped'
        WHERE workflow_definition_id = v_def_id AND code = 'SHIPPED';
    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Delivered',
        terminal_outcome = 'SUCCESS'
        WHERE workflow_definition_id = v_def_id AND code = 'DELIVERED';
    UPDATE workflow_state SET is_customer_visible = TRUE, customer_facing_label = 'Cancelled',
        terminal_outcome = 'FAILURE'
        WHERE workflow_definition_id = v_def_id AND code = 'CANCELLED';
    -- CREDIT_REVIEW intentionally left is_customer_visible = FALSE (internal-only).
END $$;

-- terminal_outcome is meaningful exactly when is_terminal is true. Added after
-- the backfill above so the pre-existing DELIVERED/CANCELLED rows already
-- satisfy it.
ALTER TABLE workflow_state
    ADD CONSTRAINT chk_terminal_outcome_consistency CHECK (
        (is_terminal = TRUE AND terminal_outcome IS NOT NULL) OR
        (is_terminal = FALSE AND terminal_outcome IS NULL)
    );
