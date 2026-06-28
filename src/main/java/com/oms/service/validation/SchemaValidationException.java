package com.oms.service.validation;

import java.util.List;

public class SchemaValidationException extends RuntimeException {

    private final List<String> violations;

    public SchemaValidationException(List<String> violations) {
        super("Schema validation failed: " + String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}
