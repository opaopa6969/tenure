# Tenure

**Entity lifecycle library with event sourcing, inspired by Pat Helland's "Life beyond Distributed Transactions" (2007).**

Born from a [DGE session](https://github.com/opaopa6969/tramli) comparing long-lived entity management with data-flow verified state machines.

## Design Philosophy

Tenure models **entities that live for years**, not flows that complete in seconds:

- **P1**: Entities are long-lived (years, not minutes)
- **P2**: Entities are message-driven (events, not method calls)
- **P3**: Idempotency is built-in (event deduplication by ID)
- **P4**: Compensation over rollback (compensating events, not undo)
- **P5**: State is a function of events (`fold(initial, events)`)
- **P6**: No cross-entity transactions

## Comparison with tramli

| Aspect | Tenure | tramli |
|--------|--------|--------|
| Central concept | Entity (long-lived) | Flow (state transitions) |
| State management | Event log (append-only) | FlowContext (mutable HashMap) |
| Idempotency | Built-in (eventId) | Not provided |
| Compensation | Compensating events | Error transitions |
| Audit trail | Complete (event log) | Partial (TransitionRecord) |
| State reconstruction | Yes (`fold`) | No |
| Data-flow verification | No | Yes (requires/produces) |

> "Data-flow verification is orthogonal to state management paradigm.
> It works on mutable contexts, event logs, and Statecharts alike."
> — DGE Session, 2026-04-08

## Quick Start

```java
var userEntity = Tenure.define("User", UserState::empty)
    .on(EmailVerified.class)
        .from("Registered").to("Verified")
        .apply((state, event) -> state.withEmail(event.email()))
    .on(PlanSelected.class)
        .from("Verified").to("Trial")
        .apply((state, event) -> state.withPlan(event.plan()))
    .on(BanIssued.class)
        .fromAny().to("Banned")
        .apply((state, event) -> state.withBanReason(event.reason()))
        .compensate(BanLifted.class)
    .build();

var user = Tenure.create(userEntity, new UserId("user-001"));
user.apply(new EmailVerified("ev-1", "alice@example.com"));
user.apply(new PlanSelected("ev-2", Plan.TRIAL));
// Idempotent: second apply with same eventId is ignored
user.apply(new EmailVerified("ev-1", "alice@example.com")); // no-op
```

## License

MIT

## Origin

Tenure was designed by Pat Helland (as a character) in a DGE dialogue session
facilitated by [@opaopa6969](https://github.com/opaopa6969), exploring what a
distributed systems expert would build if tasked with creating a state management library.
