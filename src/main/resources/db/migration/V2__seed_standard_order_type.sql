-- Seeds the STANDARD order type and its v1 workflow definition, matching the
-- example state diagram in SPEC.md §4.6, so the engine is exercisable end to end.
--
-- Convention used here (and expected by WorkflowEngineService): a transition
-- with trigger_code IS NULL is evaluated immediately on state entry, with no
-- external signal required. trigger_type is still set to a concrete value to
-- satisfy the NOT NULL/CHECK constraint, but it is not consulted for matching
-- when trigger_code is null.

DO $$
DECLARE
    v_workflow_definition_id UUID;
    v_created UUID;
    v_payment_pending UUID;
    v_credit_review UUID;
    v_fulfillment_queued UUID;
    v_shipped UUID;
    v_delivered UUID;
    v_cancelled UUID;
BEGIN
    INSERT INTO order_type (code, name, attribute_schema, line_attribute_schema, is_active)
    VALUES (
        'STANDARD',
        'Standard Order',
        '{"type":"object","properties":{"giftMessage":{"type":"string","maxLength":500}},"additionalProperties":true}'::jsonb,
        '{"type":"object","properties":{"giftWrap":{"type":"boolean"}},"additionalProperties":true}'::jsonb,
        TRUE
    );

    INSERT INTO workflow_definition (order_type_code, version, name)
    VALUES ('STANDARD', 1, 'Standard Order Workflow v1')
    RETURNING workflow_definition_id INTO v_workflow_definition_id;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'CREATED', 'AUTOMATIC', TRUE, FALSE)
    RETURNING state_id INTO v_created;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'PAYMENT_PENDING', 'AUTOMATIC', FALSE, FALSE)
    RETURNING state_id INTO v_payment_pending;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal, default_assignee_group)
    VALUES (v_workflow_definition_id, 'CREDIT_REVIEW', 'MANUAL', FALSE, FALSE, 'credit-team')
    RETURNING state_id INTO v_credit_review;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'FULFILLMENT_QUEUED', 'AUTOMATIC', FALSE, FALSE)
    RETURNING state_id INTO v_fulfillment_queued;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'SHIPPED', 'AUTOMATIC', FALSE, FALSE)
    RETURNING state_id INTO v_shipped;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'DELIVERED', 'AUTOMATIC', FALSE, TRUE)
    RETURNING state_id INTO v_delivered;

    INSERT INTO workflow_state (workflow_definition_id, code, state_type, is_initial, is_terminal)
    VALUES (v_workflow_definition_id, 'CANCELLED', 'AUTOMATIC', FALSE, TRUE)
    RETURNING state_id INTO v_cancelled;

    -- CREATED --> PAYMENT_PENDING : EVENT order.submitted
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_created, v_payment_pending, 0, 'EVENT', 'order.submitted');

    -- PAYMENT_PENDING --> CREDIT_REVIEW : guard(amount > threshold), evaluated immediately on entry
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code, guard_expression)
    VALUES (v_workflow_definition_id, v_payment_pending, v_credit_review, 0, 'EVENT', NULL,
        '{">": [{"var": "order.totalAmount"}, 1000]}');

    -- PAYMENT_PENDING --> FULFILLMENT_QUEUED : EVENT payment.captured (only reached if the guard above is false)
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_payment_pending, v_fulfillment_queued, 1, 'EVENT', 'payment.captured');

    -- CREDIT_REVIEW --> FULFILLMENT_QUEUED : TASK_APPROVED
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_credit_review, v_fulfillment_queued, 0, 'TASK_APPROVED', 'manual.approve');

    -- CREDIT_REVIEW --> CANCELLED : TASK_REJECTED
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_credit_review, v_cancelled, 0, 'TASK_REJECTED', 'manual.reject');

    -- FULFILLMENT_QUEUED --> SHIPPED : EVENT shipment.dispatched
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_fulfillment_queued, v_shipped, 0, 'EVENT', 'shipment.dispatched');

    -- SHIPPED --> DELIVERED : EVENT shipment.delivered
    INSERT INTO workflow_transition (workflow_definition_id, from_state_id, to_state_id, sequence, trigger_type, trigger_code)
    VALUES (v_workflow_definition_id, v_shipped, v_delivered, 0, 'EVENT', 'shipment.delivered');

    UPDATE order_type SET workflow_definition_id = v_workflow_definition_id WHERE code = 'STANDARD';
END $$;
