package com.tenure;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Immutable definition of an entity's lifecycle.
 *
 * State is derived from events via fold: state = fold(initial, events).
 * This is the core of Helland's approach — state is never mutated directly,
 * only events are appended, and state is recomputed.
 *
 * @param <S> State type (immutable value object)
 */
public final class EntityDefinition<S> {
    private final String name;
    private final Supplier<S> initialState;
    private final String initialStateName;
    private final List<EventHandler<S, ?>> handlers;
    private final Map<Class<?>, Class<?>> compensations;

    EntityDefinition(String name, Supplier<S> initialState, String initialStateName,
                     List<EventHandler<S, ?>> handlers, Map<Class<?>, Class<?>> compensations) {
        this.name = name;
        this.initialState = initialState;
        this.initialStateName = initialStateName;
        this.handlers = List.copyOf(handlers);
        this.compensations = Map.copyOf(compensations);
    }

    public String name() { return name; }
    public S createInitialState() { return initialState.get(); }
    public String initialStateName() { return initialStateName; }
    public List<EventHandler<S, ?>> handlers() { return handlers; }

    /** Find handler for event type + current state name. */
    @SuppressWarnings("unchecked")
    public <E extends TenureEvent> Optional<EventHandler<S, E>> findHandler(
            Class<E> eventType, String currentStateName) {
        return handlers.stream()
            .filter(h -> h.eventType().isAssignableFrom(eventType))
            .filter(h -> h.fromAny() || h.fromState().equals(currentStateName))
            .map(h -> (EventHandler<S, E>) h)
            .findFirst();
    }

    /** Check if an event type has a compensation event registered. */
    public Optional<Class<?>> compensationFor(Class<?> eventType) {
        return Optional.ofNullable(compensations.get(eventType));
    }

    /** All state names reachable from transitions. */
    public Set<String> allStateNames() {
        var names = new LinkedHashSet<String>();
        names.add(initialStateName);
        for (var h : handlers) {
            if (h.fromState() != null) names.add(h.fromState());
            names.add(h.toState());
        }
        return names;
    }

    // ─── Event Handler ──────────────────────────────────

    public record EventHandler<S, E extends TenureEvent>(
        Class<E> eventType,
        String fromState,
        boolean fromAny,
        String toState,
        BiFunction<S, E, S> reducer
    ) {}

    // ─── Builder ─────────────────────────────────────────

    public static <S> Builder<S> builder(String name, Supplier<S> initialState, String initialStateName) {
        return new Builder<>(name, initialState, initialStateName);
    }

    public static class Builder<S> {
        private final String name;
        private final Supplier<S> initialState;
        private final String initialStateName;
        private final List<EventHandler<S, ?>> handlers = new ArrayList<>();
        private final Map<Class<?>, Class<?>> compensations = new LinkedHashMap<>();

        Builder(String name, Supplier<S> initialState, String initialStateName) {
            this.name = name;
            this.initialState = initialState;
            this.initialStateName = initialStateName;
        }

        public <E extends TenureEvent> OnBuilder<S, E> on(Class<E> eventType) {
            return new OnBuilder<>(this, eventType);
        }

        <E extends TenureEvent> void addHandler(EventHandler<S, E> handler) {
            handlers.add(handler);
        }

        void addCompensation(Class<?> eventType, Class<?> compensationType) {
            compensations.put(eventType, compensationType);
        }

        public EntityDefinition<S> build() {
            return new EntityDefinition<>(name, initialState, initialStateName, handlers, compensations);
        }
    }

    public static class OnBuilder<S, E extends TenureEvent> {
        private final Builder<S> parent;
        private final Class<E> eventType;
        private String fromState;
        private boolean fromAny;

        OnBuilder(Builder<S> parent, Class<E> eventType) {
            this.parent = parent;
            this.eventType = eventType;
        }

        public OnBuilder<S, E> from(String state) { this.fromState = state; this.fromAny = false; return this; }
        public OnBuilder<S, E> fromAny() { this.fromAny = true; return this; }

        public ApplyBuilder<S, E> to(String state) {
            return new ApplyBuilder<>(parent, eventType, fromState, fromAny, state);
        }
    }

    public static class ApplyBuilder<S, E extends TenureEvent> {
        private final Builder<S> parent;
        private final Class<E> eventType;
        private final String fromState;
        private final boolean fromAny;
        private final String toState;

        ApplyBuilder(Builder<S> parent, Class<E> eventType, String fromState,
                     boolean fromAny, String toState) {
            this.parent = parent;
            this.eventType = eventType;
            this.fromState = fromState;
            this.fromAny = fromAny;
            this.toState = toState;
        }

        public CompensateBuilder<S, E> apply(BiFunction<S, E, S> reducer) {
            parent.addHandler(new EventHandler<>(eventType, fromState, fromAny, toState, reducer));
            return new CompensateBuilder<>(parent, eventType);
        }
    }

    public static class CompensateBuilder<S, E extends TenureEvent> {
        private final Builder<S> parent;
        private final Class<E> eventType;

        CompensateBuilder(Builder<S> parent, Class<E> eventType) {
            this.parent = parent;
            this.eventType = eventType;
        }

        public Builder<S> compensate(Class<? extends TenureEvent> compensationType) {
            parent.addCompensation(eventType, compensationType);
            return parent;
        }

        // Allow chaining without compensation
        public <E2 extends TenureEvent> OnBuilder<S, E2> on(Class<E2> nextEventType) {
            return parent.on(nextEventType);
        }

        public EntityDefinition<S> build() { return parent.build(); }
    }
}
