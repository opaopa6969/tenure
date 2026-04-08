package org.unlaxer.tenure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for build-time validation — tramli's #1 feature, now in Tenure.
 */
class ValidationTest {

    record State(String value) {
        static State empty() { return new State(null); }
        State with(String v) { return new State(v); }
    }

    record EventA(String eventId) implements TenureEvent {}
    record EventB(String eventId) implements TenureEvent {}
    record EventC(String eventId) implements TenureEvent {}
    record CompensateA(String eventId) implements TenureEvent {}

    @Test
    void validDefinitionBuildsWithoutError() {
        assertDoesNotThrow(() ->
            Tenure.define("Test", State::empty, "S1")
                .terminal("S3")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s.with("a"))
                .on(EventB.class).from("S2").to("S3")
                    .apply((s, e) -> s.with("b"))
                .build()
        );
    }

    @Test
    void initialStateMustNotBeTerminal() {
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "Terminal")
                .terminal("Terminal")
                .on(EventA.class).from("Start").to("Terminal")
                    .apply((s, e) -> s)
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_INITIAL_TERMINAL"));
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "S1")
                .terminal("S2")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                .on(EventB.class).from("S2").to("S3")  // S2 is terminal!
                    .apply((s, e) -> s)
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_TERMINAL_HAS_OUTGOING"));
    }

    @Test
    void compensationEventMustHaveHandler() {
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "S1")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                    .compensate(CompensateA.class) // no handler for CompensateA!
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_COMPENSATION_NO_HANDLER"));
    }

    @Test
    void ambiguousHandlersDetected() {
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "S1")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                .on(EventA.class).from("S1").to("S3")  // duplicate!
                    .apply((s, e) -> s)
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_AMBIGUOUS_HANDLER"));
    }

    @Test
    void unreachableStatesDetected() {
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "S1")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                .on(EventB.class).from("S3").to("S4")  // S3 unreachable
                    .apply((s, e) -> s)
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_UNREACHABLE"));
    }

    @Test
    void noPathToTerminalDetected() {
        // S1 → S2, but S2 has no path to terminal S3
        var ex = assertThrows(TenureException.class, () ->
            Tenure.define("Test", State::empty, "S1")
                .terminal("S3")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                // No transition from S2 to S3
                .build()
        );
        assertTrue(ex.getMessage().contains("VALIDATION_NO_PATH_TO_TERMINAL"));
    }

    @Test
    void fromAnyMakesStatesReachable() {
        // S3 is only reachable via fromAny — should NOT be flagged as unreachable
        assertDoesNotThrow(() ->
            Tenure.define("Test", State::empty, "S1")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                .on(EventB.class).fromAny().to("S3")
                    .apply((s, e) -> s)
                .build()
        );
    }

    @Test
    void warningsForFromAnyShadowing() {
        var def = Tenure.define("Test", State::empty, "S1")
                .on(EventA.class).from("S1").to("S2")
                    .apply((s, e) -> s)
                .on(EventA.class).fromAny().to("S3")  // shadows the specific S1→S2
                    .apply((s, e) -> s)
                .build();
        assertFalse(def.warnings().isEmpty());
        assertTrue(def.warnings().get(0).contains("fromAny"));
    }
}
