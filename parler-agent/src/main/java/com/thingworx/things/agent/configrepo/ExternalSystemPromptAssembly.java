package com.thingworx.things.agent.configrepo;

import com.thingworx.things.agent.LeadingStablePromptComposer;
import com.thingworx.things.agent.PromptContextCacheSnapshot;

/** Resolves the model-facing leading stable system prompt for default vs external-file modes. */
public final class ExternalSystemPromptAssembly {

    private ExternalSystemPromptAssembly() {}

    public static String resolveLeadingStableSystemPrompt(
            ExternalSystemPromptSelection externalSelection,
            String systemPromptOverride,
            String agentSettingsSystemPrompt,
            boolean appendRoutingGuide,
            PromptContextCacheSnapshot snapshot,
            String taxonomyPromptInjectionEffective,
            String workflowCatalog) {
        if (externalSelection != null && externalSelection.isActiveExternal()) {
            return externalSelection.promptText().trim();
        }
        String first = (systemPromptOverride != null && !systemPromptOverride.trim().isEmpty())
                ? systemPromptOverride.trim()
                : (agentSettingsSystemPrompt != null ? agentSettingsSystemPrompt : "");
        return LeadingStablePromptComposer.assemble(first, appendRoutingGuide, snapshot,
                taxonomyPromptInjectionEffective, workflowCatalog);
    }

    /** Resolves leading stable text from one committed snapshot (inspection + turn assembly). */
    public static String resolveLeadingStableFromSnapshot(
            PromptContextCacheSnapshot snapshot,
            String systemPromptOverride,
            String agentSettingsSystemPrompt,
            boolean appendRoutingGuide,
            String taxonomyPromptInjectionEffective,
            String workflowCatalog) {
        ExternalSystemPromptSelection external = snapshot != null
                ? snapshot.getExternalSystemPrompt()
                : ExternalSystemPromptSelection.defaultSelection();
        return resolveLeadingStableSystemPrompt(external, systemPromptOverride, agentSettingsSystemPrompt,
                appendRoutingGuide, snapshot, taxonomyPromptInjectionEffective, workflowCatalog);
    }
}
