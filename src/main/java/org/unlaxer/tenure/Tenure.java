package org.unlaxer.tenure;

import java.util.function.Supplier;

/**
 * Entry point for the Tenure entity lifecycle library.
 *
 * Tenure is a superset of tramli: it combines tramli's build-time validation,
 * lifecycle graph analysis, and diagram generation with event sourcing,
 * idempotency, compensation, and time travel.
 *
 * <pre>
 * var userDef = Tenure.define("User", UserState::empty, "Registered")
 *     .terminal("Deleted")
 *     .on(EmailVerified.class).from("Registered").to("Verified")
 *         .guard((state, event) -> event.email().contains("@")
 *             ? GuardResult.accepted() : GuardResult.rejected("Invalid email"))
 *         .apply((state, event) -> state.withEmail(event.email()))
 *     .build();  // ← 8 validation checks
 *
 * var user = Tenure.create(userDef, "user-001");
 * user.apply(new EmailVerified("ev-1", "alice@example.com"));
 *
 * // tramli can't do this:
 * var v1 = user.stateAtVersion(1);
 *
 * // lifecycle analysis (like tramli's DataFlowGraph):
 * var graph = userDef.lifecycleGraph();
 * graph.reachableFrom("Registered");
 * graph.impactOf(BanIssued.class);
 *
 * // Mermaid diagram (like tramli's MermaidGenerator):
 * String mermaid = userDef.toMermaid();
 * </pre>
 */
public final class Tenure {

    private Tenure() {}

    /** Define a new entity type with build-time validation. */
    public static <S> EntityDefinition.Builder<S> define(
            String name, Supplier<S> initialState, String initialStateName) {
        return EntityDefinition.builder(name, initialState, initialStateName);
    }

    /** Create a new entity instance. */
    public static <S> EntityInstance<S> create(EntityDefinition<S> definition, String entityId) {
        return new EntityInstance<>(entityId, definition);
    }
}
