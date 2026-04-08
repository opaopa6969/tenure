package org.unlaxer.tenure;

/**
 * Base interface for all events in Tenure.
 * Every event has an ID for idempotency and a name for routing.
 */
public interface TenureEvent {
    /** Unique event ID for idempotency. Same ID = same event = skip. */
    String eventId();

    /** Event type name, used for transition routing. */
    default String eventName() {
        return getClass().getSimpleName();
    }
}
