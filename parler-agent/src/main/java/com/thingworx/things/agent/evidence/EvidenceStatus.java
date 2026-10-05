package com.thingworx.things.agent.evidence;

/**
 * EG4 analytical status vocabulary. Distinct from row-level {@code ok}/{@code error} on
 * {@link com.thingworx.things.agent.taskstate.AgentTaskEvidence}.
 */
public enum EvidenceStatus {
    SUCCESS,
    NO_FINDING,
    INSUFFICIENT_EVIDENCE,
    ERROR
}
