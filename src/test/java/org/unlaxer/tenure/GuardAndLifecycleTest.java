package org.unlaxer.tenure;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for guards, entry/exit actions, terminal states,
 * lifecycle graph, and Mermaid generation.
 */
class GuardAndLifecycleTest {

    // ─── State & Events ─────────────────────────────────

    record OrderState(String item, int amount, boolean isPaid, String cancelReason) {
        static OrderState empty() { return new OrderState(null, 0, false, null); }
        OrderState withItem(String i) { return new OrderState(i, amount, isPaid, cancelReason); }
        OrderState withAmount(int a) { return new OrderState(item, a, isPaid, cancelReason); }
        OrderState markPaid() { return new OrderState(item, amount, true, cancelReason); }
        OrderState cancelled(String r) { return new OrderState(item, amount, isPaid, r); }
    }

    record OrderPlaced(String eventId, String item, int amount) implements TenureEvent {}
    record PaymentReceived(String eventId, int amount) implements TenureEvent {}
    record OrderShipped(String eventId) implements TenureEvent {}
    record OrderCancelled(String eventId, String reason) implements TenureEvent {}

    // ─── Definition with guards & lifecycle ──────────────

    EntityDefinition<OrderState> orderDef(List<String> lifecycle) {
        return Tenure.define("Order", OrderState::empty, "Created")
            .terminal("Shipped", "Cancelled")
            .onStateEnter("Paid", s -> lifecycle.add("enter:Paid"))
            .onStateExit("Created", s -> lifecycle.add("exit:Created"))
            .onStateEnter("Cancelled", s -> lifecycle.add("enter:Cancelled"))
            .on(OrderPlaced.class).from("Created").to("Pending")
                .apply((s, e) -> s.withItem(e.item()).withAmount(e.amount()))
            .on(PaymentReceived.class).from("Pending").to("Paid")
                .guard((state, event) -> event.amount() >= state.amount()
                    ? EventGuard.GuardResult.accepted()
                    : EventGuard.GuardResult.rejected("Insufficient payment: need "
                        + state.amount() + ", got " + event.amount()))
                .apply((s, e) -> s.markPaid())
            .on(OrderShipped.class).from("Paid").to("Shipped")
                .apply((s, e) -> s)
            .on(OrderCancelled.class).fromAny().to("Cancelled")
                .apply((s, e) -> s.cancelled(e.reason()))
            .build();
    }

    // ─── Guard Tests ────────────────────────────────────

    @Test
    void guardAcceptsValidEvent() {
        var order = Tenure.create(orderDef(new ArrayList<>()), "order-001");
        order.apply(new OrderPlaced("ev-1", "Widget", 100));

        var result = order.apply(new PaymentReceived("ev-2", 100));
        assertEquals(EntityInstance.ApplyResult.APPLIED, result);
        assertTrue(order.state().isPaid());
    }

    @Test
    void guardRejectsInvalidEvent() {
        var order = Tenure.create(orderDef(new ArrayList<>()), "order-001");
        order.apply(new OrderPlaced("ev-1", "Widget", 100));

        var result = order.apply(new PaymentReceived("ev-2", 50)); // too little
        assertTrue(result.isRejected());
        assertInstanceOf(EntityInstance.ApplyResult.Rejected.class, result);
        var rejected = (EntityInstance.ApplyResult.Rejected) result;
        assertTrue(rejected.reason().contains("Insufficient payment"));
        assertFalse(order.state().isPaid()); // state unchanged
        assertEquals("Pending", order.stateName());
    }

    @Test
    void guardRejectionDoesNotConsumeEventId() {
        var order = Tenure.create(orderDef(new ArrayList<>()), "order-001");
        order.apply(new OrderPlaced("ev-1", "Widget", 100));
        order.apply(new PaymentReceived("ev-2", 50)); // rejected

        // Same eventId should still work (wasn't consumed)
        var result = order.apply(new PaymentReceived("ev-2", 100));
        assertEquals(EntityInstance.ApplyResult.APPLIED, result);
    }

    // ─── Terminal State Tests ───────────────────────────

    @Test
    void terminalStateBlocksFurtherEvents() {
        var order = Tenure.create(orderDef(new ArrayList<>()), "order-001");
        order.apply(new OrderPlaced("ev-1", "Widget", 100));
        order.apply(new OrderCancelled("ev-2", "Changed mind"));
        assertEquals("Cancelled", order.stateName());
        assertTrue(order.isTerminal());

        // No more events allowed
        var result = order.apply(new OrderPlaced("ev-3", "Another", 200));
        assertEquals(EntityInstance.ApplyResult.TERMINAL, result);
    }

    // ─── Entry/Exit Action Tests ────────────────────────

    @Test
    void entryExitActionsFireOnStateChange() {
        var lifecycle = new ArrayList<String>();
        var order = Tenure.create(orderDef(lifecycle), "order-001");

        order.apply(new OrderPlaced("ev-1", "Widget", 100));
        assertTrue(lifecycle.contains("exit:Created"));

        order.apply(new PaymentReceived("ev-2", 100));
        assertTrue(lifecycle.contains("enter:Paid"));
    }

    @Test
    void entryActionFiresForCancellation() {
        var lifecycle = new ArrayList<String>();
        var order = Tenure.create(orderDef(lifecycle), "order-001");
        order.apply(new OrderCancelled("ev-1", "spam"));
        assertTrue(lifecycle.contains("enter:Cancelled"));
    }

    // ─── Lifecycle Graph Tests ──────────────────────────

    @Test
    void lifecycleGraphReachability() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();

        var reachable = graph.reachableFrom("Created");
        assertTrue(reachable.contains("Pending"));
        assertTrue(reachable.contains("Paid"));
        assertTrue(reachable.contains("Shipped"));
        assertTrue(reachable.contains("Cancelled"));
    }

    @Test
    void lifecycleGraphEventsAtState() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();

        var events = graph.eventsAt("Created");
        assertTrue(events.contains(OrderPlaced.class));
        assertTrue(events.contains(OrderCancelled.class)); // fromAny

        var pendingEvents = graph.eventsAt("Pending");
        assertTrue(pendingEvents.contains(PaymentReceived.class));
        assertTrue(pendingEvents.contains(OrderCancelled.class)); // fromAny
    }

    @Test
    void lifecycleGraphNoDeadStates() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();
        assertTrue(graph.deadStates().isEmpty());
    }

    @Test
    void lifecycleGraphTerminalStates() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();
        assertTrue(graph.terminalStates().contains("Shipped"));
        assertTrue(graph.terminalStates().contains("Cancelled"));
    }

    @Test
    void lifecycleGraphShortestPath() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();

        var path = graph.shortestPath("Created", "Shipped");
        assertEquals(3, path.size()); // Created→Pending→Paid→Shipped
        assertEquals("Created", path.get(0).fromState());
        assertEquals("Pending", path.get(0).toState());
        assertEquals("Shipped", path.get(2).toState());
    }

    @Test
    void lifecycleGraphImpactAnalysis() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();

        var impact = graph.impactOf(OrderCancelled.class);
        // fromAny → affects many states
        assertTrue(impact.affectedStates().contains("Cancelled"));
        assertTrue(impact.transitionCount() > 1);
    }

    @Test
    void lifecycleGraphRequiredEvents() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();

        var required = graph.requiredEventsTo("Paid");
        assertEquals(2, required.size());
        assertEquals(OrderPlaced.class, required.get(0));
        assertEquals(PaymentReceived.class, required.get(1));
    }

    @Test
    void lifecycleGraphGuardedTransitions() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();
        var guarded = graph.guardedTransitions();

        // PaymentReceived has a guard
        assertTrue(guarded.stream().anyMatch(t ->
                t.eventType().equals(PaymentReceived.class)));
    }

    @Test
    void lifecycleGraphReachesTo() {
        var graph = orderDef(new ArrayList<>()).lifecycleGraph();
        var sources = graph.reachesTo("Shipped");
        assertTrue(sources.contains("Paid"));
        assertTrue(sources.contains("Pending"));
        assertTrue(sources.contains("Created"));
    }

    // ─── Mermaid Tests ──────────────────────────────────

    @Test
    void mermaidGeneration() {
        var mermaid = orderDef(new ArrayList<>()).toMermaid();
        assertTrue(mermaid.contains("stateDiagram-v2"));
        assertTrue(mermaid.contains("[*] --> Created"));
        assertTrue(mermaid.contains("Shipped --> [*]"));
        assertTrue(mermaid.contains("Cancelled --> [*]"));
        assertTrue(mermaid.contains("OrderPlaced"));
        assertTrue(mermaid.contains("PaymentReceived"));
        assertTrue(mermaid.contains("[guarded]")); // guard annotation
    }

    @Test
    void mermaidEventFlow() {
        var eventFlow = MermaidGenerator.generateEventFlow(orderDef(new ArrayList<>()));
        assertTrue(eventFlow.contains("flowchart LR"));
        assertTrue(eventFlow.contains("OrderPlaced"));
    }

    // ─── State Name at Version ──────────────────────────

    @Test
    void stateNameAtVersion() {
        var order = Tenure.create(orderDef(new ArrayList<>()), "order-001");
        assertEquals("Created", order.stateNameAtVersion(0));

        order.apply(new OrderPlaced("ev-1", "Widget", 100));
        assertEquals("Pending", order.stateNameAtVersion(1));

        order.apply(new PaymentReceived("ev-2", 100));
        assertEquals("Paid", order.stateNameAtVersion(2));
    }
}
