package org.unlaxer.tenure;

/**
 * Pure validation function for events before they are applied.
 * Equivalent to tramli's TransitionGuard — validates external input
 * before allowing a state transition.
 *
 * Guards must be pure: no I/O, no side effects, no state mutation.
 *
 * @param <S> State type
 * @param <E> Event type
 */
@FunctionalInterface
public interface EventGuard<S, E extends TenureEvent> {

    /**
     * Validate whether the event should be accepted in the current state.
     *
     * @param state   current entity state (read-only)
     * @param event   the incoming event
     * @return GuardResult indicating acceptance or rejection
     */
    GuardResult validate(S state, E event);

    // ─── Guard Result (sealed) ──────────────────────────

    sealed interface GuardResult permits GuardResult.Accepted, GuardResult.Rejected {

        record Accepted() implements GuardResult {}
        record Rejected(String reason) implements GuardResult {}

        static GuardResult accepted() { return new Accepted(); }
        static GuardResult rejected(String reason) { return new Rejected(reason); }
    }
}
