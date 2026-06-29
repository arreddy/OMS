package com.oms.domain.workflow;

/**
 * Derived, not persisted — mirrors the badge color mapping in
 * order-management-system-ui-spec.md §6. Computed from state_type / is_terminal
 * / terminal_outcome rather than stored, since it's fully determined by them.
 */
public enum BadgeCategory {
    AUTOMATIC,
    MANUAL,
    WAIT,
    TERMINAL_SUCCESS,
    TERMINAL_FAILURE;

    public static BadgeCategory of(StateType stateType, boolean terminal, TerminalOutcome terminalOutcome) {
        if (terminal) {
            return terminalOutcome == TerminalOutcome.SUCCESS ? TERMINAL_SUCCESS : TERMINAL_FAILURE;
        }
        return switch (stateType) {
            case AUTOMATIC -> AUTOMATIC;
            case MANUAL -> MANUAL;
            case WAIT -> WAIT;
        };
    }
}
