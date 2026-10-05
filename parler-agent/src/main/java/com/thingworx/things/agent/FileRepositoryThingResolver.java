package com.thingworx.things.agent;

import java.util.Optional;

import org.slf4j.Logger;

import com.thingworx.entities.RootEntity;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * Resolves an AgentThing FileRepository setting to a live {@link FileRepositoryThing}.
 *
 * <p>Empty name — no lookup and no log; missing Thing or non-{@code FileRepositoryThing} — log at error and return
 * empty; never throws to callers.
 */
public final class FileRepositoryThingResolver {

    private FileRepositoryThingResolver() {}

    /**
     * @param configuredName raw FileRepository Thing name (may be null/blank)
     * @param log            agent logger
     * @param agentThingName owning AgentThing name (log prefix)
     * @return non-empty only when the Thing exists and is a {@link FileRepositoryThing}
     */
    public static Optional<FileRepositoryThing> resolve(String configuredName, Logger log, String agentThingName) {
        return resolve(configuredName, log, agentThingName, true, false);
    }

    /**
     * Resolution for a tool that reads the repository on the user's behalf: the Thing must be visible to the current
     * user ({@link PlatformAccess#findAsUser}).
     */
    public static Optional<FileRepositoryThing> resolveForCurrentUser(String configuredName, Logger log,
            String agentThingName) {
        return resolve(configuredName, log, agentThingName, true, true);
    }

    /** Readiness-only name/type resolution with no diagnostic side effects. */
    public static boolean resolvesQuietly(String configuredName) {
        return resolve(configuredName, null, null, false, false).isPresent();
    }

    /** {@link #resolvesQuietly} for the current user's view of the repository. */
    public static boolean resolvesQuietlyForCurrentUser(String configuredName) {
        return resolve(configuredName, null, null, false, true).isPresent();
    }

    private static Optional<FileRepositoryThing> resolve(String configuredName, Logger log, String agentThingName,
            boolean logFailures, boolean asCurrentUser) {
        if (configuredName == null || configuredName.isBlank()) {
            return Optional.empty();
        }
        String name = configuredName.trim();
        try {
            RootEntity ent = asCurrentUser
                    ? PlatformAccess.findAsUser(name, RelationshipTypes.ThingworxRelationshipTypes.Thing)
                    : PlatformAccess.findProgrammatic(name, RelationshipTypes.ThingworxRelationshipTypes.Thing);
            if (ent == null) {
                if (logFailures) {
                    log.error("[{}] FileRepository setting: Thing not found: {}", agentThingName, name);
                }
                return Optional.empty();
            }
            if (!(ent instanceof Thing)) {
                if (logFailures) {
                    log.error("[{}] FileRepository setting: configured name is not a Thing: {}", agentThingName, name);
                }
                return Optional.empty();
            }
            Thing thing = (Thing) ent;
            if (!(thing instanceof FileRepositoryThing)) {
                if (logFailures) {
                    log.error("[{}] FileRepository setting: Thing {} is not a FileRepository Thing", agentThingName, name);
                }
                return Optional.empty();
            }
            return Optional.of((FileRepositoryThing) thing);
        } catch (LinkageError e) {
            if (logFailures) {
                log.error("[{}] FileRepository setting: platform linkage error resolving {}: {}",
                        agentThingName, name, e.getMessage(), e);
            }
            return Optional.empty();
        } catch (Exception e) {
            if (logFailures) {
                log.error("[{}] FileRepository setting: failed to resolve {}: {}", agentThingName, name,
                        e.getMessage(), e);
            }
            return Optional.empty();
        }
    }
}
