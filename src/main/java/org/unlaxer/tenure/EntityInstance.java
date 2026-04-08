package org.unlaxer.tenure;

import java.time.Instant;
import java.util.*;

/**
 * A live entity instance. State is derived from the event log via fold.
 *
 * Core invariant: the event log is the source of truth.
 * {@code state() == fold(initialState, eventLog)}
 *
 * Idempotency: events with duplicate eventIds are silently ignored.
 *
 * Enhanced with tramli-inspired features:
 * <ul>
 *   <li>Guards — validate events before applying</li>
 *   <li>Entry/exit actions — lifecycle callbacks on state change</li>
 *   <li>Terminal state awareness — prevent events after terminal</li>
 * </ul>
 *
 * @param <S> State type (immutable value object)
 */
public final class EntityInstance<S> {
    private final String entityId;
    private final EntityDefinition<S> definition;
    private final List<EventRecord> eventLog = new ArrayList<>();
    private final Set<String> seenEventIds = new LinkedHashSet<>();
    private S currentState;
    private String currentStateName;

    EntityInstance(String entityId, EntityDefinition<S> definition) {
        this.entityId = entityId;
        this.definition = definition;
        this.currentState = definition.createInitialState();
        this.currentStateName = definition.initialStateName();
    }

    public String entityId() { return entityId; }
    public S state() { return currentState; }
    public String stateName() { return currentStateName; }
    public List<EventRecord> eventLog() { return Collections.unmodifiableList(eventLog); }
    public int version() { return eventLog.size(); }
    public EntityDefinition<S> definition() { return definition; }

    /** Check if the entity is in a terminal state. */
    public boolean isTerminal() {
        return definition.isTerminal(currentStateName);
    }

    /**
     * Apply an event to this entity.
     *
     * Execution order:
     * 1. Idempotency check (P3)
     * 2. Terminal state check
     * 3. Find handler
     * 4. Guard validation (if guard exists)
     * 5. Exit action (if state changes)
     * 6. Apply reducer
     * 7. Record event
     * 8. Entry action (if state changes)
     *
     * @return ApplyResult indicating what happened
     */
    @SuppressWarnings("unchecked")
    public <E extends TenureEvent> ApplyResult apply(E event) {
        Objects.requireNonNull(event, "Event must not be null");

        // P3: Idempotency — skip duplicate eventIds
        if (seenEventIds.contains(event.eventId())) {
            return ApplyResult.DUPLICATE;
        }

        // Terminal state — no more transitions
        if (isTerminal()) {
            return ApplyResult.TERMINAL;
        }

        // Find handler
        var handler = (EntityDefinition.EventHandler<S, E>)
            definition.findHandler(event.getClass(), currentStateName).orElse(null);

        if (handler == null) {
            return ApplyResult.NO_HANDLER;
        }

        // Guard validation
        if (handler.guard() != null) {
            var guardResult = handler.guard().validate(currentState, event);
            if (guardResult instanceof EventGuard.GuardResult.Rejected rejected) {
                return ApplyResult.rejected(rejected.reason());
            }
        }

        // Apply reducer: new state = reducer(current, event)
        String fromState = currentStateName;
        String toState = handler.toState();
        boolean stateChanges = !fromState.equals(toState);

        // Exit action
        if (stateChanges) {
            var exitAction = definition.exitActions().get(fromState);
            if (exitAction != null) exitAction.accept(currentState);
        }

        S newState = handler.reducer().apply(currentState, event);

        // Record event
        eventLog.add(new EventRecord(
            event.eventId(), event.eventName(), fromState, toState, Instant.now(), event));
        seenEventIds.add(event.eventId());

        // Update derived state
        currentState = newState;
        currentStateName = toState;

        // Entry action
        if (stateChanges) {
            var entryAction = definition.entryActions().get(toState);
            if (entryAction != null) entryAction.accept(currentState);
        }

        return ApplyResult.APPLIED;
    }

    /**
     * Rebuild state from scratch by replaying all events.
     * Useful after deserialization of the event log.
     * Note: guards and entry/exit actions are NOT re-executed during rebuild.
     */
    public void rebuild() {
        currentState = definition.createInitialState();
        currentStateName = definition.initialStateName();
        seenEventIds.clear();

        for (EventRecord record : eventLog) {
            seenEventIds.add(record.eventId());
            @SuppressWarnings("unchecked")
            var handler = (EntityDefinition.EventHandler<S, TenureEvent>)
                definition.findHandler(record.event().getClass(), record.fromState()).orElse(null);
            if (handler != null) {
                @SuppressWarnings("unchecked")
                var event = (TenureEvent) record.event();
                currentState = handler.reducer().apply(currentState, event);
                currentStateName = handler.toState();
            }
        }
    }

    /**
     * Get the state as it was at a specific version (event count).
     * This is only possible with event sourcing — tramli can't do this.
     */
    public S stateAtVersion(int version) {
        if (version < 0 || version > eventLog.size()) {
            throw new TenureException("INVALID_VERSION", "Version " + version + " out of range");
        }
        S state = definition.createInitialState();
        String stateName = definition.initialStateName();
        for (int i = 0; i < version; i++) {
            EventRecord record = eventLog.get(i);
            @SuppressWarnings("unchecked")
            var handler = (EntityDefinition.EventHandler<S, TenureEvent>)
                definition.findHandler(record.event().getClass(), record.fromState()).orElse(null);
            if (handler != null) {
                state = handler.reducer().apply(state, (TenureEvent) record.event());
                stateName = handler.toState();
            }
        }
        return state;
    }

    /**
     * Get the state name at a specific version.
     */
    public String stateNameAtVersion(int version) {
        if (version < 0 || version > eventLog.size()) {
            throw new TenureException("INVALID_VERSION", "Version " + version + " out of range");
        }
        if (version == 0) return definition.initialStateName();
        return eventLog.get(version - 1).toState();
    }

    /**
     * Check if an event type has a compensation registered.
     */
    public boolean isCompensable(Class<? extends TenureEvent> eventType) {
        return definition.compensationFor(eventType).isPresent();
    }

    // ─── Types ──────────────────────────────────────────

    public sealed interface ApplyResult {
        record Applied() implements ApplyResult {}
        record Duplicate() implements ApplyResult {}
        record NoHandler() implements ApplyResult {}
        record Terminal() implements ApplyResult {}
        record Rejected(String reason) implements ApplyResult {}

        ApplyResult APPLIED = new Applied();
        ApplyResult DUPLICATE = new Duplicate();
        ApplyResult NO_HANDLER = new NoHandler();
        ApplyResult TERMINAL = new Terminal();

        static ApplyResult rejected(String reason) { return new Rejected(reason); }

        default boolean isApplied() { return this instanceof Applied; }
        default boolean isRejected() { return this instanceof Rejected; }
    }

    public record EventRecord(
        String eventId,
        String eventName,
        String fromState,
        String toState,
        Instant timestamp,
        TenureEvent event
    ) {
        @Override public String toString() {
            return fromState + " --[" + eventName + " " + eventId + "]--> " + toState;
        }
    }
}
