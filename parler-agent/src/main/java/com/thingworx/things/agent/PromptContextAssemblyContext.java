package com.thingworx.things.agent;

import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;

/** Inputs for leading stable prompt assembly shared by runtime and offline tests. */
public final class PromptContextAssemblyContext {

    private final String agentSettingsSystemPrompt;
    private final boolean appendBuiltInToolRoutingGuide;
    private final String taxonomyPromptInjectionEffective;
    private final PlaybookRegistrySnapshot playbookRegistry;

    public PromptContextAssemblyContext(
            String agentSettingsSystemPrompt,
            boolean appendBuiltInToolRoutingGuide,
            String taxonomyPromptInjectionEffective,
            PlaybookRegistrySnapshot playbookRegistry) {
        this.agentSettingsSystemPrompt = agentSettingsSystemPrompt != null ? agentSettingsSystemPrompt : "";
        this.appendBuiltInToolRoutingGuide = appendBuiltInToolRoutingGuide;
        this.taxonomyPromptInjectionEffective = taxonomyPromptInjectionEffective != null
                ? taxonomyPromptInjectionEffective
                : "full_table";
        this.playbookRegistry = playbookRegistry != null ? playbookRegistry : PlaybookRegistrySnapshot.empty(
                java.time.Instant.EPOCH);
    }

    public String agentSettingsSystemPrompt() {
        return agentSettingsSystemPrompt;
    }

    public boolean appendBuiltInToolRoutingGuide() {
        return appendBuiltInToolRoutingGuide;
    }

    public String taxonomyPromptInjectionEffective() {
        return taxonomyPromptInjectionEffective;
    }

    public PlaybookRegistrySnapshot playbookRegistry() {
        return playbookRegistry;
    }
}
