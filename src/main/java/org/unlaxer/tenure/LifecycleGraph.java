package org.unlaxer.tenure;

import java.util.*;

/**
 * Static analysis of an entity's lifecycle, analogous to tramli's DataFlowGraph.
 *
 * While tramli tracks data types through processors (requires/produces),
 * Tenure tracks events through states. The analysis questions are the same:
 *
 * <ul>
 *   <li>What events can fire at a given state?</li>
 *   <li>What states are reachable from a given state?</li>
 *   <li>Are there dead states that can never be reached?</li>
 *   <li>If I change an event type, what states are impacted?</li>
 *   <li>What is the shortest path between two states?</li>
 *   <li>What events have been applied to reach a given state?</li>
 * </ul>
 *
 * Built automatically via {@code EntityDefinition.lifecycleGraph()}.
 */
public final class LifecycleGraph {

    private final Set<String> allStates;
    private final String initialState;
    private final Set<String> terminalStates;

    // state → outgoing transitions
    private final Map<String, List<Transition>> outgoing;
    // state → incoming transitions
    private final Map<String, List<Transition>> incoming;
    // event type → transitions using it
    private final Map<Class<?>, List<Transition>> eventIndex;

    private LifecycleGraph(Set<String> allStates, String initialState, Set<String> terminalStates,
                           Map<String, List<Transition>> outgoing,
                           Map<String, List<Transition>> incoming,
                           Map<Class<?>, List<Transition>> eventIndex) {
        this.allStates = Set.copyOf(allStates);
        this.initialState = initialState;
        this.terminalStates = Set.copyOf(terminalStates);
        this.outgoing = outgoing;
        this.incoming = incoming;
        this.eventIndex = eventIndex;
    }

    /** Build from an EntityDefinition. */
    static <S> LifecycleGraph from(EntityDefinition<S> def) {
        var allStates = def.allStateNames();
        var outgoing = new LinkedHashMap<String, List<Transition>>();
        var incoming = new LinkedHashMap<String, List<Transition>>();
        var eventIndex = new LinkedHashMap<Class<?>, List<Transition>>();

        for (var state : allStates) {
            outgoing.put(state, new ArrayList<>());
            incoming.put(state, new ArrayList<>());
        }

        for (var h : def.handlers()) {
            if (h.fromAny()) {
                // fromAny creates transitions from every non-terminal state
                for (var state : allStates) {
                    if (def.terminalStates().contains(state)) continue;
                    var t = new Transition(state, h.toState(), h.eventType(), true, h.guard() != null);
                    outgoing.computeIfAbsent(state, k -> new ArrayList<>()).add(t);
                    incoming.computeIfAbsent(h.toState(), k -> new ArrayList<>()).add(t);
                    eventIndex.computeIfAbsent(h.eventType(), k -> new ArrayList<>()).add(t);
                }
            } else {
                var t = new Transition(h.fromState(), h.toState(), h.eventType(), false, h.guard() != null);
                outgoing.computeIfAbsent(h.fromState(), k -> new ArrayList<>()).add(t);
                incoming.computeIfAbsent(h.toState(), k -> new ArrayList<>()).add(t);
                eventIndex.computeIfAbsent(h.eventType(), k -> new ArrayList<>()).add(t);
            }
        }

        return new LifecycleGraph(allStates, def.initialStateName(), def.terminalStates(),
                outgoing, incoming, eventIndex);
    }

    // ─── Query: States ──────────────────────────────────

    /** All states in the entity lifecycle. */
    public Set<String> allStates() { return allStates; }

    /** The initial state. */
    public String initialState() { return initialState; }

    /** Terminal states (lifecycle endpoints). */
    public Set<String> terminalStates() { return terminalStates; }

    /** States reachable from the given state (BFS forward). */
    public Set<String> reachableFrom(String state) {
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(state);
        visited.add(state);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var t : outgoing.getOrDefault(current, List.of())) {
                if (visited.add(t.toState())) {
                    queue.add(t.toState());
                }
            }
        }
        visited.remove(state); // don't include the starting state
        return visited;
    }

    /** States from which the given state is reachable (BFS backward). */
    public Set<String> reachesTo(String state) {
        var visited = new LinkedHashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(state);
        visited.add(state);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var t : incoming.getOrDefault(current, List.of())) {
                if (visited.add(t.fromState())) {
                    queue.add(t.fromState());
                }
            }
        }
        visited.remove(state);
        return visited;
    }

    /** States with no incoming transitions (other than initial). */
    public Set<String> orphanStates() {
        var orphans = new LinkedHashSet<String>();
        for (var state : allStates) {
            if (state.equals(initialState)) continue;
            if (incoming.getOrDefault(state, List.of()).isEmpty()) {
                orphans.add(state);
            }
        }
        return orphans;
    }

    /** States that are unreachable from the initial state. */
    public Set<String> deadStates() {
        var reachable = reachableFrom(initialState);
        reachable.add(initialState);
        var dead = new LinkedHashSet<>(allStates);
        dead.removeAll(reachable);
        return dead;
    }

    // ─── Query: Events ──────────────────────────────────

    /** Events that can fire at the given state. */
    public Set<Class<?>> eventsAt(String state) {
        var events = new LinkedHashSet<Class<?>>();
        for (var t : outgoing.getOrDefault(state, List.of())) {
            events.add(t.eventType());
        }
        return events;
    }

    /** All event types used in the lifecycle. */
    public Set<Class<?>> allEventTypes() {
        return Collections.unmodifiableSet(eventIndex.keySet());
    }

    /** Transitions triggered by the given event type. */
    public List<Transition> transitionsFor(Class<?> eventType) {
        return List.copyOf(eventIndex.getOrDefault(eventType, List.of()));
    }

    // ─── Query: Transitions ─────────────────────────────

    /** All outgoing transitions from a state. */
    public List<Transition> outgoingFrom(String state) {
        return List.copyOf(outgoing.getOrDefault(state, List.of()));
    }

    /** All incoming transitions to a state. */
    public List<Transition> incomingTo(String state) {
        return List.copyOf(incoming.getOrDefault(state, List.of()));
    }

    // ─── Query: Impact Analysis ─────────────────────────

    /**
     * If a given event type is changed, which states are impacted?
     * Returns source and target states of all transitions using this event.
     */
    public Impact impactOf(Class<?> eventType) {
        var transitions = eventIndex.getOrDefault(eventType, List.of());
        var affectedStates = new LinkedHashSet<String>();
        for (var t : transitions) {
            affectedStates.add(t.fromState());
            affectedStates.add(t.toState());
        }
        return new Impact(eventType, affectedStates, transitions.size());
    }

    /**
     * Shortest path (event sequence) from one state to another.
     * Returns empty if no path exists.
     */
    public List<Transition> shortestPath(String from, String to) {
        if (from.equals(to)) return List.of();

        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        var parent = new HashMap<String, Transition>();

        queue.add(from);
        visited.add(from);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var t : outgoing.getOrDefault(current, List.of())) {
                if (visited.add(t.toState())) {
                    parent.put(t.toState(), t);
                    if (t.toState().equals(to)) {
                        // Reconstruct path
                        var path = new ArrayList<Transition>();
                        var step = to;
                        while (parent.containsKey(step)) {
                            var trans = parent.get(step);
                            path.add(trans);
                            step = trans.fromState();
                        }
                        Collections.reverse(path);
                        return path;
                    }
                    queue.add(t.toState());
                }
            }
        }
        return List.of(); // no path
    }

    /**
     * Events that must have occurred to reach a given state
     * (on the shortest path from initial).
     */
    public List<Class<?>> requiredEventsTo(String state) {
        var path = shortestPath(initialState, state);
        return path.stream().map(Transition::eventType).toList();
    }

    // ─── Query: Guarded Transitions ─────────────────────

    /** All transitions that have guards. */
    public List<Transition> guardedTransitions() {
        var guarded = new ArrayList<Transition>();
        for (var transitions : outgoing.values()) {
            for (var t : transitions) {
                if (t.hasGuard()) guarded.add(t);
            }
        }
        return guarded;
    }

    // ─── Types ──────────────────────────────────────────

    /** A single transition in the lifecycle. */
    public record Transition(
        String fromState,
        String toState,
        Class<?> eventType,
        boolean fromAny,
        boolean hasGuard
    ) {
        @Override public String toString() {
            return fromState + " --[" + eventType.getSimpleName() + "]--> " + toState;
        }
    }

    /** Impact analysis result for a single event type. */
    public record Impact(
        Class<?> eventType,
        Set<String> affectedStates,
        int transitionCount
    ) {}
}
