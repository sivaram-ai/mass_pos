package com.masspos.counter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The counter settings window refuses a bad number itself, before the sign-in screen has to. */
class CounterCodeTest {

    @Test
    void acceptsTheShapeTheServerAccepts() {
        assertThat(CounterConfig.codeProblem("T2")).isNull();
        assertThat(CounterConfig.codeProblem(" c1 ")).isNull();
        assertThat(CounterConfig.codeProblem("A")).isNull();
    }

    @Test
    void refusesANameInTheNumberBoxAndSaysWhere() {
        assertThat(CounterConfig.codeProblem("MAIN")).contains("\"MAIN\"", "up to three characters", "Name box");
        assertThat(CounterConfig.codeProblem("2T")).isNotNull();
        assertThat(CounterConfig.codeProblem("")).contains("Give this counter a number");
        assertThat(CounterConfig.codeProblem(null)).isNotNull();
    }
}
