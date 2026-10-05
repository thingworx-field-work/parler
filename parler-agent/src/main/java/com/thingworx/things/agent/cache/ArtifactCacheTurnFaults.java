package com.thingworx.things.agent.cache;

import org.json.JSONObject;

/**
 * Shared turn-boundary policy for typed Artifact Cache faults.
 *
 * <p>Legacy tool helpers sometimes wrap cache faults (for example an {@code InputStream} must
 * expose an {@code IOException}) or catch broad {@code Exception}. This class keeps the fatal
 * repository classification code-based and bounded without parsing exception messages.
 */
public final class ArtifactCacheTurnFaults {

    public static final String PUBLIC_REPOSITORY_UNAVAILABLE_CODE =
            "ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE";

    public static final String PUBLIC_REPOSITORY_UNAVAILABLE_MESSAGE =
            "Artifact Cache FileRepository is unavailable. Verify "
                    + "AgentSettings.artifactCacheFileRepository, repair the configured FileRepository, and retry.";

    private ArtifactCacheTurnFaults() {}

    /** Finds the first typed cache fault in a bounded cause chain, or {@code null}. */
    public static ArtifactCacheException find(Throwable fault) {
        Throwable current = fault;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ArtifactCacheException) {
                return (ArtifactCacheException) current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    public static boolean isRepositoryUnavailable(Throwable fault) {
        return findRepositoryUnavailable(fault) != null;
    }

    /** Rethrows a repository-wide fault found in {@code fault}; leaves other faults to the caller. */
    public static void rethrowRepositoryUnavailable(Throwable fault) {
        ArtifactCacheException typed = findRepositoryUnavailable(fault);
        if (typed != null) {
            throw typed;
        }
    }

    /** Finds a repository-wide typed fault anywhere in a bounded cause chain. */
    public static ArtifactCacheException findRepositoryUnavailable(Throwable fault) {
        Throwable current = fault;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ArtifactCacheException
                    && ((ArtifactCacheException) current).code()
                            == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                return (ArtifactCacheException) current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    /** Bounded structured tool evidence for an artifact-local cache fault. */
    public static String toolErrorJson(ArtifactCacheException fault) {
        JSONObject out = new JSONObject();
        out.put("status", "error");
        out.put("code", fault != null ? fault.code().name() : ArtifactCacheFaultCode.INTERNAL.name());
        out.put("message", boundedMessage(fault));
        return out.toString();
    }

    /** Paired result for the tool whose cache operation proved the repository unavailable. */
    public static String repositoryUnavailableToolErrorJson() {
        JSONObject out = new JSONObject();
        out.put("status", "error");
        out.put("code", ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE.name());
        out.put("message", "Artifact Cache repository became unavailable during tool execution.");
        return out.toString();
    }

    /** Truthful replay row for a sibling that was emitted by the LLM but not executed. */
    public static String repositoryUnavailableSkippedToolJson() {
        JSONObject out = new JSONObject();
        out.put("status", "skipped");
        out.put("code", PUBLIC_REPOSITORY_UNAVAILABLE_CODE);
        out.put("message", "Skipped because the Artifact Cache repository became unavailable.");
        return out.toString();
    }

    private static String boundedMessage(ArtifactCacheException fault) {
        if (fault == null || fault.getMessage() == null) {
            return "Artifact Cache operation failed.";
        }
        String message = fault.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
