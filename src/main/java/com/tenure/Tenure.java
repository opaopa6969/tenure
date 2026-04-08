package com.tenure;

import java.util.function.Supplier;

/**
 * Entry point for the Tenure entity lifecycle library.
 *
 * <pre>
 * var userDef = Tenure.define("User", UserState::empty, "Registered")
 *     .on(EmailVerified.class).from("Registered").to("Verified")
 *         .apply((state, event) -> state.withEmail(event.email()))
 *     .build();
 *
 * var user = Tenure.create(userDef, "user-001");
 * user.apply(new EmailVerified("ev-1", "alice@example.com"));
 * </pre>
 */
public final class Tenure {

    private Tenure() {}

    /** Define a new entity type. */
    public static <S> EntityDefinition.Builder<S> define(
            String name, Supplier<S> initialState, String initialStateName) {
        return EntityDefinition.builder(name, initialState, initialStateName);
    }

    /** Create a new entity instance. */
    public static <S> EntityInstance<S> create(EntityDefinition<S> definition, String entityId) {
        return new EntityInstance<>(entityId, definition);
    }
}
