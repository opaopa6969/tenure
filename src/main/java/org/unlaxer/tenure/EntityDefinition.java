package org.unlaxer.tenure;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Immutable, validated definition of an entity's lifecycle.
 *
 * State is derived from events via fold: state = fold(initial, events).
 * This is the core of Helland's approach — state is never mutated directly,
 * only events are appended, and state is recomputed.
 *
 * Like tramli's FlowDefinition, build() performs structural validation
 * to catch errors at definition time rather than runtime:
 *
 * <ol>
 *   <li>All non-terminal states reachable from initial</li>
 *   <li>Path from initial to terminal exists (if terminals declared)</li>
 *   <li>No ambiguous handlers (same event type from same state)</li>
 *   <li>Compensation events have handlers</li>
 *   <li>Terminal states have no outgoing transitions</li>
 *   <li>All target states are known (no dangling references)</li>
 *   <li>Exactly one initial state</li>
 *   <li>fromAny handlers don't shadow specific handlers</li>
 * </ol>
 *
 * @param <S> State type (immutable value object)
 */
public final class EntityDefinition<S> {
    private final String name;
    private final Supplier<S> initialState;
    private final String initialStateName;
    private final Set<String> terminalStates;
    private final List<EventHandler<S, ?>> handlers;
    private final Map<Class<?>, Class<?>> compensations;
    private final Map<String, Consumer<S>> entryActions;
    private final Map<String, Consumer<S>> exitActions;
    private final List<String> warnings;

    EntityDefinition(String name, Supplier<S> initialState, String initialStateName,
                     Set<String> terminalStates,
                     List<EventHandler<S, ?>> handlers, Map<Class<?>, Class<?>> compensations,
                     Map<String, Consumer<S>> entryActions, Map<String, Consumer<S>> exitActions,
                     List<String> warnings) {
        this.name = name;
        this.initialState = initialState;
        this.initialStateName = initialStateName;
        this.terminalStates = Set.copyOf(terminalStates);
        this.handlers = List.copyOf(handlers);
        this.compensations = Map.copyOf(compensations);
        this.entryActions = Map.copyOf(entryActions);
        this.exitActions = Map.copyOf(exitActions);
        this.warnings = List.copyOf(warnings);
    }

    public String name() { return name; }
    public S createInitialState() { return initialState.get(); }
    public String initialStateName() { return initialStateName; }
    public Set<String> terminalStates() { return terminalStates; }
    public List<EventHandler<S, ?>> handlers() { return handlers; }
    public Map<String, Consumer<S>> entryActions() { return entryActions; }
    public Map<String, Consumer<S>> exitActions() { return exitActions; }
    public List<String> warnings() { return warnings; }

    public boolean isTerminal(String stateName) {
        return terminalStates.contains(stateName);
    }

    /** Find handler for event type + current state name. */
    @SuppressWarnings("unchecked")
    public <E extends TenureEvent> Optional<EventHandler<S, E>> findHandler(
            Class<E> eventType, String currentStateName) {
        // Prefer specific handler over fromAny
        EventHandler<S, ?> specific = null;
        EventHandler<S, ?> wildcard = null;
        for (var h : handlers) {
            if (!h.eventType().isAssignableFrom(eventType)) continue;
            if (!h.fromAny() && h.fromState().equals(currentStateName)) {
                specific = h;
                break;
            }
            if (h.fromAny() && wildcard == null) {
                wildcard = h;
            }
        }
        var found = specific != null ? specific : wildcard;
        return Optional.ofNullable((EventHandler<S, E>) found);
    }

    /** Check if an event type has a compensation event registered. */
    public Optional<Class<?>> compensationFor(Class<?> eventType) {
        return Optional.ofNullable(compensations.get(eventType));
    }

    /** All state names reachable from transitions. */
    public Set<String> allStateNames() {
        var names = new LinkedHashSet<String>();
        names.add(initialStateName);
        names.addAll(terminalStates);
        for (var h : handlers) {
            if (h.fromState() != null) names.add(h.fromState());
            names.add(h.toState());
        }
        return names;
    }

    /** Build the lifecycle graph for static analysis. */
    public LifecycleGraph lifecycleGraph() {
        return LifecycleGraph.from(this);
    }

    /** Generate Mermaid state diagram. */
    public String toMermaid() {
        return MermaidGenerator.generate(this);
    }

    // ─── Event Handler ──────────────────────────────────

    public record EventHandler<S, E extends TenureEvent>(
        Class<E> eventType,
        String fromState,
        boolean fromAny,
        String toState,
        BiFunction<S, E, S> reducer,
        EventGuard<S, E> guard
    ) {
        /** Handler without guard (backward compatible). */
        public EventHandler(Class<E> eventType, String fromState, boolean fromAny,
                            String toState, BiFunction<S, E, S> reducer) {
            this(eventType, fromState, fromAny, toState, reducer, null);
        }
    }

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
        private final Set<String> terminalStates = new LinkedHashSet<>();
        private final Map<String, Consumer<S>> entryActions = new LinkedHashMap<>();
        private final Map<String, Consumer<S>> exitActions = new LinkedHashMap<>();

        Builder(String name, Supplier<S> initialState, String initialStateName) {
            this.name = name;
            this.initialState = initialState;
            this.initialStateName = initialStateName;
        }

        /** Declare a terminal state (no outgoing transitions allowed). */
        public Builder<S> terminal(String... stateNames) {
            terminalStates.addAll(Arrays.asList(stateNames));
            return this;
        }

        /** Register an action to run when entering a state. */
        public Builder<S> onStateEnter(String stateName, Consumer<S> action) {
            entryActions.put(stateName, action);
            return this;
        }

        /** Register an action to run when exiting a state. */
        public Builder<S> onStateExit(String stateName, Consumer<S> action) {
            exitActions.put(stateName, action);
            return this;
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

        /**
         * Build and validate the entity definition.
         * Runs 8 structural checks — fails fast with actionable error messages.
         *
         * @throws TenureException if validation fails
         */
        public EntityDefinition<S> build() {
            var warnings = new ArrayList<String>();
            var def = new EntityDefinition<>(name, initialState, initialStateName,
                    terminalStates, handlers, compensations, entryActions, exitActions, warnings);
            var validationWarnings = DefinitionValidator.validate(def);
            warnings.addAll(validationWarnings);
            return new EntityDefinition<>(name, initialState, initialStateName,
                    terminalStates, handlers, compensations, entryActions, exitActions, warnings);
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
        private EventGuard<S, E> guard;

        ApplyBuilder(Builder<S> parent, Class<E> eventType, String fromState,
                     boolean fromAny, String toState) {
            this.parent = parent;
            this.eventType = eventType;
            this.fromState = fromState;
            this.fromAny = fromAny;
            this.toState = toState;
        }

        /** Add a guard that validates the event before applying. */
        public ApplyBuilder<S, E> guard(EventGuard<S, E> guard) {
            this.guard = guard;
            return this;
        }

        public CompensateBuilder<S, E> apply(BiFunction<S, E, S> reducer) {
            parent.addHandler(new EventHandler<>(eventType, fromState, fromAny, toState, reducer, guard));
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

        /** Declare terminal states. */
        public Builder<S> terminal(String... stateNames) {
            return parent.terminal(stateNames);
        }

        /** Register an action to run when entering a state. */
        public Builder<S> onStateEnter(String stateName, Consumer<S> action) {
            return parent.onStateEnter(stateName, action);
        }

        /** Register an action to run when exiting a state. */
        public Builder<S> onStateExit(String stateName, Consumer<S> action) {
            return parent.onStateExit(stateName, action);
        }

        public EntityDefinition<S> build() { return parent.build(); }
    }
}
