package org.unlaxer.tenure;

/**
 * Generates Mermaid state diagrams from EntityDefinition.
 * Diagrams are generated from code — they can never be out of date.
 *
 * Equivalent to tramli's MermaidGenerator, adapted for event-sourced entities.
 */
public final class MermaidGenerator {

    private MermaidGenerator() {}

    /**
     * Generate a Mermaid stateDiagram-v2 from an EntityDefinition.
     *
     * <pre>
     * String mermaid = MermaidGenerator.generate(userDef);
     * // Output:
     * // stateDiagram-v2
     * //   [*] --> Registered
     * //   Registered --> Verified : EmailVerified
     * //   Verified --> Trial : PlanSelected
     * //   ...
     * //   Deleted --> [*]
     * </pre>
     */
    public static <S> String generate(EntityDefinition<S> def) {
        var sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");
        sb.append("  direction LR\n");

        // Initial state
        sb.append("  [*] --> ").append(def.initialStateName()).append('\n');

        // Terminal states styling
        for (var terminal : def.terminalStates()) {
            sb.append("  ").append(terminal).append(" --> [*]\n");
        }

        // Transitions
        for (var h : def.handlers()) {
            String eventName = h.eventType().getSimpleName();
            String label = h.guard() != null ? eventName + " [guarded]" : eventName;

            if (h.fromAny()) {
                // fromAny: show transitions from all non-terminal states
                for (var state : def.allStateNames()) {
                    if (def.terminalStates().contains(state)) continue;
                    if (state.equals(h.toState())) continue; // skip self-loop for clarity
                    sb.append("  ").append(state).append(" --> ").append(h.toState())
                            .append(" : ").append(label).append('\n');
                }
            } else {
                sb.append("  ").append(h.fromState()).append(" --> ").append(h.toState())
                        .append(" : ").append(label).append('\n');
            }
        }

        // Entry/exit annotations
        for (var entry : def.entryActions().entrySet()) {
            sb.append("  note right of ").append(entry.getKey()).append(" : entry action\n");
        }
        for (var entry : def.exitActions().entrySet()) {
            sb.append("  note left of ").append(entry.getKey()).append(" : exit action\n");
        }

        // Compensation annotations
        for (var h : def.handlers()) {
            var comp = def.compensationFor(h.eventType());
            if (comp.isPresent()) {
                sb.append("  note right of ").append(h.toState())
                        .append(" : compensate: ").append(comp.get().getSimpleName()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Generate a Mermaid flowchart showing event flow through states.
     * This is the data-flow equivalent — shows which events connect which states.
     */
    public static <S> String generateEventFlow(EntityDefinition<S> def) {
        var sb = new StringBuilder();
        sb.append("flowchart LR\n");

        // State nodes
        for (var state : def.allStateNames()) {
            String shape = def.terminalStates().contains(state) ? "(((" + state + ")))" :
                    state.equals(def.initialStateName()) ? "([" + state + "])" :
                            "[" + state + "]";
            sb.append("  ").append(sanitize(state)).append(shape).append('\n');
        }

        // Event edges
        for (var h : def.handlers()) {
            String eventName = h.eventType().getSimpleName();
            if (h.fromAny()) {
                for (var state : def.allStateNames()) {
                    if (def.terminalStates().contains(state)) continue;
                    if (state.equals(h.toState())) continue;
                    sb.append("  ").append(sanitize(state)).append(" -->|")
                            .append(eventName).append("| ").append(sanitize(h.toState())).append('\n');
                }
            } else {
                sb.append("  ").append(sanitize(h.fromState())).append(" -->|")
                        .append(eventName).append("| ").append(sanitize(h.toState())).append('\n');
            }
        }

        return sb.toString();
    }

    private static String sanitize(String state) {
        return state.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
