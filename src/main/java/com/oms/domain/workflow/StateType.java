package com.oms.domain.workflow;

/**
 * SPEC.md §4.2. AUTOMATIC and WAIT are evaluated identically by the engine
 * (guard/trigger matching on entry or signal) — they differ only in expected
 * dwell time, which is operational/monitoring information, not engine logic.
 * Only MANUAL changes engine behavior: it creates a Task and blocks exit on
 * a task decision.
 */
public enum StateType {
    AUTOMATIC,
    MANUAL,
    WAIT
}
