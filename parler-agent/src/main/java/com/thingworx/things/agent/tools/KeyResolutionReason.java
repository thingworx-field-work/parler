package com.thingworx.things.agent.tools;

/**
 * Stable wire/log values for {@link ModelKeyResolver.ParentResolution#getReasonEnum()} and optional
 * {@code query_entities} {@code keyResolution.reason} (see {@code docs/agent/key-resolution.md} §10).
 */
public enum KeyResolutionReason {

    /** Phase −1: taxonomy {@code Synonyms} matched; parent kind/name taken from that row. */
    TAXONOMY_SYNONYM,

    /** Phase 0.5: exact name matched on ThingTemplates[{@code GenericThing}].{@code GetIncomingDependencies} rows. */
    GENERIC_THING_INCOMING_DEPENDENCY,

    /** Phase 1a step 1: {@link com.thingworx.things.agent.PlatformAccess#findAsUser} on trimmed raw key. */
    EXACT_RAW_LOOKUP,

    /** Phase 1a step 2: Phase 0 hint table canonical name (e.g. {@code stream} → {@code Stream}). */
    HINT_TABLE,

    /** Phase 1a step 3: first-character uppercase on Phase-0–normalized key. */
    CAPITALIZE_NORMALIZED,

    /** Phase 1b: {@code EntityServices.GetEntityList} with {@code *key*} mask + §3 ranking. */
    WILDCARD_GET_ENTITY_LIST
}
