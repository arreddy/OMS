package com.oms.exception;

/** Optimistic-lock mismatches and invalid-state transitions both surface as 409 (SPEC.md §6). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
