package org.unlaxer.tenure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserLifecycleTest {

    // ─── State (immutable value object) ─────────────────

    record UserState(String email, String plan, String banReason, boolean active) {
        static UserState empty() { return new UserState(null, null, null, false); }
        UserState withEmail(String e) { return new UserState(e, plan, banReason, active); }
        UserState withPlan(String p) { return new UserState(email, p, banReason, true); }
        UserState withBan(String r) { return new UserState(email, plan, r, false); }
        UserState unbanned() { return new UserState(email, plan, null, true); }
    }

    // ─── Events ─────────────────────────────────────────

    record EmailVerified(String eventId, String email) implements TenureEvent {}
    record PlanSelected(String eventId, String plan) implements TenureEvent {}
    record PaymentReceived(String eventId, int amount) implements TenureEvent {}
    record BanIssued(String eventId, String reason) implements TenureEvent {}
    record BanLifted(String eventId) implements TenureEvent {}
    record AccountDeleted(String eventId) implements TenureEvent {}

    // ─── Definition ─────────────────────────────────────

    EntityDefinition<UserState> userDef() {
        return Tenure.define("User", UserState::empty, "Registered")
            .terminal("Deleted")
            .on(EmailVerified.class).from("Registered").to("Verified")
                .apply((state, event) -> state.withEmail(event.email()))
            .on(PlanSelected.class).from("Verified").to("Trial")
                .apply((state, event) -> state.withPlan(event.plan()))
            .on(PaymentReceived.class).from("Trial").to("Active")
                .apply((state, event) -> state.withPlan(state.plan()))  // keep plan, activate
            .on(BanIssued.class).fromAny().to("Banned")
                .apply((state, event) -> state.withBan(event.reason()))
                .compensate(BanLifted.class)
            .on(BanLifted.class).from("Banned").to("Active")
                .apply((state, event) -> state.unbanned())
            .on(AccountDeleted.class).fromAny().to("Deleted")
                .apply((state, event) -> state)
            .build();
    }

    // ─── Original Tests (preserved) ─────────────────────

    @Test
    void happyPath() {
        var user = Tenure.create(userDef(), "user-001");
        assertEquals("Registered", user.stateName());

        user.apply(new EmailVerified("ev-1", "alice@example.com"));
        assertEquals("Verified", user.stateName());
        assertEquals("alice@example.com", user.state().email());

        user.apply(new PlanSelected("ev-2", "pro"));
        assertEquals("Trial", user.stateName());
        assertEquals("pro", user.state().plan());

        user.apply(new PaymentReceived("ev-3", 1000));
        assertEquals("Active", user.stateName());
        assertTrue(user.state().active());
    }

    @Test
    void idempotency() {
        var user = Tenure.create(userDef(), "user-001");
        var result1 = user.apply(new EmailVerified("ev-1", "alice@example.com"));
        assertEquals(EntityInstance.ApplyResult.APPLIED, result1);

        // Same eventId → idempotent skip
        var result2 = user.apply(new EmailVerified("ev-1", "alice@example.com"));
        assertEquals(EntityInstance.ApplyResult.DUPLICATE, result2);

        assertEquals(1, user.version());
        assertEquals("Verified", user.stateName());
    }

    @Test
    void banFromAnyState() {
        var user = Tenure.create(userDef(), "user-001");
        user.apply(new EmailVerified("ev-1", "alice@example.com"));
        user.apply(new PlanSelected("ev-2", "pro"));
        assertEquals("Trial", user.stateName());

        // Ban from Trial (fromAny)
        user.apply(new BanIssued("ev-3", "spam"));
        assertEquals("Banned", user.stateName());
        assertEquals("spam", user.state().banReason());
        assertFalse(user.state().active());
    }

    @Test
    void compensation() {
        var user = Tenure.create(userDef(), "user-001");
        user.apply(new EmailVerified("ev-1", "a@b.com"));
        user.apply(new PlanSelected("ev-2", "pro"));
        user.apply(new PaymentReceived("ev-3", 1000));
        assertEquals("Active", user.stateName());

        // Ban
        user.apply(new BanIssued("ev-4", "fraud"));
        assertEquals("Banned", user.stateName());

        // Compensate: lift ban
        assertTrue(user.isCompensable(BanIssued.class));
        user.apply(new BanLifted("ev-5"));
        assertEquals("Active", user.stateName());
        assertTrue(user.state().active());
    }

    @Test
    void eventLog() {
        var user = Tenure.create(userDef(), "user-001");
        user.apply(new EmailVerified("ev-1", "a@b.com"));
        user.apply(new PlanSelected("ev-2", "pro"));

        assertEquals(2, user.eventLog().size());
        assertEquals("Registered", user.eventLog().get(0).fromState());
        assertEquals("Verified", user.eventLog().get(0).toState());
        assertEquals("EmailVerified", user.eventLog().get(0).eventName());
    }

    @Test
    void stateAtVersion() {
        var user = Tenure.create(userDef(), "user-001");
        user.apply(new EmailVerified("ev-1", "a@b.com"));
        user.apply(new PlanSelected("ev-2", "pro"));
        user.apply(new PaymentReceived("ev-3", 1000));

        // Time travel: what was state at version 1?
        var v1 = user.stateAtVersion(1);
        assertEquals("a@b.com", v1.email());
        assertNull(v1.plan());

        // Current
        assertEquals("pro", user.state().plan());
    }

    @Test
    void rebuild() {
        var user = Tenure.create(userDef(), "user-001");
        user.apply(new EmailVerified("ev-1", "a@b.com"));
        user.apply(new PlanSelected("ev-2", "pro"));

        // Rebuild from event log
        user.rebuild();
        assertEquals("Trial", user.stateName());
        assertEquals("a@b.com", user.state().email());
        assertEquals("pro", user.state().plan());
    }

    @Test
    void noHandlerForWrongState() {
        var user = Tenure.create(userDef(), "user-001");
        // Can't select plan before verifying email
        var result = user.apply(new PlanSelected("ev-1", "pro"));
        assertEquals(EntityInstance.ApplyResult.NO_HANDLER, result);
        assertEquals("Registered", user.stateName());
    }

    @Test
    void allStateNames() {
        var states = userDef().allStateNames();
        assertTrue(states.contains("Registered"));
        assertTrue(states.contains("Verified"));
        assertTrue(states.contains("Trial"));
        assertTrue(states.contains("Active"));
        assertTrue(states.contains("Banned"));
        assertTrue(states.contains("Deleted"));
    }
}
