package com.thingworx.things.agent.configrepo;

import java.util.Optional;

import org.slf4j.Logger;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * Per {@code AgentLoop.run()} execution cache for parsed {@code invoke_service} allow policy (see
 * {@code docs/agent/configuration-repository.md}); cleared with {@link com.thingworx.things.agent.tools.AgentToolContext#clear()}.
 */
public final class InvokeServicePolicyBatchCache {

    private static final ThreadLocal<InvokeServiceAllowPolicy> POLICY = new ThreadLocal<>();

    private InvokeServicePolicyBatchCache() {}

    public static void clear() {
        POLICY.remove();
    }

    /**
     * Loads once per AgentLoop execution from the configured FileRepository.
     */
    public static InvokeServiceAllowPolicy getOrLoad(AgentThing agent, Logger log) {
        InvokeServiceAllowPolicy existing = POLICY.get();
        if (existing != null) {
            return existing;
        }
        InvokeServiceAllowPolicy parsed = loadFresh(agent, log);
        POLICY.set(parsed);
        return parsed;
    }

    private static InvokeServiceAllowPolicy loadFresh(AgentThing agent, Logger log) {
        String repoName = agent.getConfigurationRepositoryThingName();
        if (repoName == null || repoName.isBlank()) {
            return InvokeServiceAllowPolicy.FILE_MISSING;
        }
        Optional<FileRepositoryThing> fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (fr.isEmpty()) {
            return InvokeServiceAllowPolicy.FILE_MISSING;
        }
        try {
            RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(fr.get());
            RepositoryTextLoads.Result tr =
                    RepositoryTextLoads.loadText(reader, ConfigurationRepositoryPaths.INVOKE_SERVICE_POLICY);
            if (tr.kind() == RepositoryTextLoads.Kind.MISSING || tr.kind() == RepositoryTextLoads.Kind.EMPTY) {
                return InvokeServiceAllowPolicy.FILE_MISSING;
            }
            if (tr.kind() == RepositoryTextLoads.Kind.READ_ERROR) {
                log.error("[{}] invoke_service policy read failed: {}", agent.getName(), tr.errorMessage());
                return InvokeServiceAllowPolicy.INVALID;
            }
            return InvokeServiceAllowPolicy.parseJsonOrInvalid(tr.text(), log);
        } catch (Exception e) {
            log.error("[{}] invoke_service policy load failed: {}", agent.getName(), e.getMessage(), e);
            return InvokeServiceAllowPolicy.INVALID;
        }
    }
}
