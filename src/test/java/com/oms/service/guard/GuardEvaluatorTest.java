package com.oms.service.guard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardEvaluatorTest {

    private final GuardEvaluator evaluator = new GuardEvaluator();

    @Test
    void nullExpressionAlwaysPasses() {
        assertThat(evaluator.evaluate(null, Map.of())).isTrue();
    }

    @Test
    void blankExpressionAlwaysPasses() {
        assertThat(evaluator.evaluate("   ", Map.of())).isTrue();
    }

    @Test
    void evaluatesTrueWhenAboveThreshold() {
        Map<String, Object> context = Map.of("order", Map.of("totalAmount", 1500.0));
        assertThat(evaluator.evaluate("{\">\": [{\"var\": \"order.totalAmount\"}, 1000]}", context)).isTrue();
    }

    @Test
    void evaluatesFalseWhenBelowThreshold() {
        Map<String, Object> context = Map.of("order", Map.of("totalAmount", 500.0));
        assertThat(evaluator.evaluate("{\">\": [{\"var\": \"order.totalAmount\"}, 1000]}", context)).isFalse();
    }

    @Test
    void malformedExpressionThrows() {
        assertThatThrownBy(() -> evaluator.evaluate("not json", Map.of()))
                .isInstanceOf(RuntimeException.class);
    }
}
