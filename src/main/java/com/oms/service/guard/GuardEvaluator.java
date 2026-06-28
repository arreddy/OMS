package com.oms.service.guard;

import io.github.jamsesso.jsonlogic.JsonLogic;
import io.github.jamsesso.jsonlogic.JsonLogicException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evaluates workflow_transition.guard_expression (SPEC.md §4.3, §9 — JSON
 * Logic chosen as the guard DSL). A null/blank expression always passes.
 */
@Component
public class GuardEvaluator {

    private final JsonLogic jsonLogic = new JsonLogic();

    public boolean evaluate(String guardExpression, Map<String, Object> context) {
        if (guardExpression == null || guardExpression.isBlank()) {
            return true;
        }
        try {
            Object result = jsonLogic.apply(guardExpression, context);
            return JsonLogic.truthy(result);
        } catch (JsonLogicException e) {
            throw new IllegalStateException("Invalid guard expression: " + guardExpression, e);
        }
    }
}
