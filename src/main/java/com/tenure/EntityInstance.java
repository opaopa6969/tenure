package com.tenure;

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

    /**
     * Apply an event to this entity.
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

        // Find handler
        var handler = (EntityDefinition.EventHandler<S, E>)
            definition.findHandler(event.getClass(), currentStateName).orElse(null);

        if (handler == null) {
            return ApplyResult.NO_HANDLER;
        }

        // Apply reducer: new state = reducer(current, event)
        String fromState = currentStateName;
        S newState = handler.reducer().apply(currentState, event);
        String toState = handler.toState();

        // Record event
        eventLog.add(new EventRecord(
            event.eventId(), event.eventName(), fromState, toState, Instant.now(), event));
        seenEventIds.add(event.eventId());

        // Update derived state
        currentState = newState;
        currentStateName = toState;

        return ApplyResult.APPLIED;
    }

    /**
     * Rebuild state from scratch by replaying all events.
     * Useful after deserialization of the event log.
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
     * Check if an event type has a compensation registered.
     */
    public boolean isCompensable(Class<? extends TenureEvent> eventType) {
        return definition.compensationFor(eventType).isPresent();
    }

    // ─── Types ──────────────────────────────────────────

    public enum ApplyResult {
        APPLIED,      // Event was applied, state changed
        DUPLICATE,    // Event ID already seen, idempotent skip
        NO_HANDLER    // No handler for this event in current state
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
