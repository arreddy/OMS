package com.oms.domain.workflow;

/** Only set when WorkflowState#terminal is true — see chk_terminal_outcome_consistency. */
public enum TerminalOutcome {
    SUCCESS,
    FAILURE
}
