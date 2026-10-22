package io.tokenpilot.client;

/** Which Control Plane endpoint receives usage events. */
public enum IngestionApi {

    /**
     * {@code POST /api/ingestion/events/batch} (default). Up to 100 events per request with the client's own costs.
     * Rows land in the existing usage tables, not in the Observe ledger.
     */
    INGESTION_BATCH,

    /**
     * {@code POST /api/v2/observe/events}: the Observe ledger. One event per request; the server prices the tokens
     * from its own catalog and records an event it cannot price as unpriced rather than 0 USD. Costs, total
     * tokens and pricing version are not sent because the endpoint does not take them; metadata (including
     * {@code trace_id}) is sent, and servers from before v2 accepted it ignore it. Prompt and completion counts
     * must include cached and reasoning tokens. The server must
     * enable the endpoint ({@code token-pilot.observe.ingestion.enabled=true}); otherwise every event is refused
     * with 404.
     */
    OBSERVE_V2
}
