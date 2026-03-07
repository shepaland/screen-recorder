package com.prg.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OAuthStateStoreTest {

    private OAuthStateStore stateStore;

    @BeforeEach
    void setUp() {
        stateStore = new OAuthStateStore();
    }

    @Test
    @DisplayName("generateAndStore should return non-null, non-blank state")
    void testGenerateAndStore() {
        String state = stateStore.generateAndStore();

        assertThat(state).isNotNull();
        assertThat(state).isNotBlank();
        assertThat(state).hasSize(36); // UUID format
    }

    @Test
    @DisplayName("validateAndConsume should return true for valid state and consume it")
    void testValidateAndConsume() {
        String state = stateStore.generateAndStore();

        // First validation should succeed
        boolean result = stateStore.validateAndConsume(state);
        assertThat(result).isTrue();

        // Second validation of same state should fail (already consumed)
        boolean result2 = stateStore.validateAndConsume(state);
        assertThat(result2).isFalse();
    }

    @Test
    @DisplayName("validateAndConsume should return false for unknown state")
    void testInvalidState() {
        boolean result = stateStore.validateAndConsume("not-a-real-state-value");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("validateAndConsume should return false for null state")
    void testNullState() {
        boolean result = stateStore.validateAndConsume(null);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("validateAndConsume should return false for blank state")
    void testBlankState() {
        boolean result = stateStore.validateAndConsume("");
        assertThat(result).isFalse();

        boolean result2 = stateStore.validateAndConsume("   ");
        assertThat(result2).isFalse();
    }

    @Test
    @DisplayName("Multiple states can be stored and validated independently")
    void testMultipleStates() {
        String state1 = stateStore.generateAndStore();
        String state2 = stateStore.generateAndStore();
        String state3 = stateStore.generateAndStore();

        // All should be different
        assertThat(state1).isNotEqualTo(state2);
        assertThat(state2).isNotEqualTo(state3);

        // Validate in different order
        assertThat(stateStore.validateAndConsume(state2)).isTrue();
        assertThat(stateStore.validateAndConsume(state1)).isTrue();
        assertThat(stateStore.validateAndConsume(state3)).isTrue();

        // All consumed now
        assertThat(stateStore.validateAndConsume(state1)).isFalse();
        assertThat(stateStore.validateAndConsume(state2)).isFalse();
        assertThat(stateStore.validateAndConsume(state3)).isFalse();
    }
}
