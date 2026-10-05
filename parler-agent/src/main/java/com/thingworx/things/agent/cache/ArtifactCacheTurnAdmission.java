package com.thingworx.things.agent.cache;

import java.util.Objects;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentBaseThing;

/**
 * Shared, side-effect-free public admission policy for the mandatory Artifact Cache repository.
 */
public final class ArtifactCacheTurnAdmission {

    public static final String SETTING_NAME = "AgentSettings.artifactCacheFileRepository";
    public static final String NOT_CONFIGURED_CODE = "ARTIFACT_CACHE_NOT_CONFIGURED";
    public static final String NOT_CONFIGURED_MESSAGE =
            "Artifact Cache FileRepository is not configured. Configure " + SETTING_NAME
                    + " with a dedicated FileRepository, edit/save the AgentThing, and retry.";

    private ArtifactCacheTurnAdmission() {}

    /** Immutable decision consumed by every public AgentThing entry adapter. */
    public static final class Decision {
        private final String errorCode;
        private final String message;
        private final String repositoryThingName;

        private Decision(String errorCode, String message, String repositoryThingName) {
            this.errorCode = errorCode;
            this.message = message;
            this.repositoryThingName = repositoryThingName;
        }

        public boolean isReady() {
            return errorCode == null;
        }

        public String errorCode() {
            return errorCode;
        }

        public String message() {
            return message;
        }

        public String repositoryThingName() {
            return repositoryThingName;
        }

        /** Bounded ThingWorx Service error text for synchronous Chat. */
        public String serviceExceptionMessage() {
            return isReady() ? "" : "[" + errorCode + "] " + message;
        }
    }

    public static Decision evaluate(AgentBaseThing agent, Logger log) {
        return fromReadiness(ArtifactCacheCore.readiness(agent, log));
    }

    static Decision fromReadiness(ArtifactCacheCore.Readiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        switch (readiness.status()) {
            case READY:
                return new Decision(null, null, readiness.repositoryThingName());
            case NOT_CONFIGURED:
                return new Decision(NOT_CONFIGURED_CODE, NOT_CONFIGURED_MESSAGE, null);
            case REPOSITORY_UNAVAILABLE:
            default:
                return new Decision(ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_CODE,
                        ArtifactCacheTurnFaults.PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE,
                        readiness.repositoryThingName());
        }
    }

    /** Initialization diagnostic only: never throws and never creates or probes a cache file. */
    public static void logInitialization(AgentBaseThing agent, Logger log) {
        if (log == null) {
            return;
        }
        Decision decision = evaluate(agent, log);
        String agentName = agent != null ? agent.getName() : "-";
        if (decision.isReady()) {
            log.info("ARTIFACT_CACHE_READINESS agentThing={} status=READY setting={} configuredRepository={}",
                    agentName, SETTING_NAME, safeField(decision.repositoryThingName()));
            return;
        }
        log.error("ARTIFACT_CACHE_READINESS agentThing={} status=ERROR code={} setting={} configuredRepository={}",
                agentName, decision.errorCode(), SETTING_NAME, safeField(decision.repositoryThingName()));
    }

    /** One bounded warning per rejected public request; no stack trace or repository path is logged. */
    public static void logRejected(Logger log, String agentThingName, String entryPath,
            String requestId, String conversationId, Decision decision) {
        if (log == null || decision == null || decision.isReady()) {
            return;
        }
        log.warn("ARTIFACT_CACHE_TURN_REJECTED agentThing={} entryPath={} code={} requestId={} "
                        + "conversationId={} setting={} configuredRepository={}",
                safeField(agentThingName), safeField(entryPath), decision.errorCode(), safeField(requestId),
                safeField(conversationId), SETTING_NAME, safeField(decision.repositoryThingName()));
    }

    private static String safeField(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        String normalized = value.trim().replace('\n', '_').replace('\r', '_');
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200);
    }
}
