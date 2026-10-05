package com.thingworx.things.agent.skillregistry;

import java.util.ArrayList;
import java.util.List;

import com.thingworx.things.agent.playbook.PlaybookCatalogEntry;
import com.thingworx.things.agent.playbook.PlaybookCatalogFormatter;
import com.thingworx.things.agent.playbook.PlaybookRegistrySnapshot;

/**
 * Combined skill + playbook metadata catalog for the leading stable system row.
 */
public final class AgentWorkflowCatalogFormatter {

    private AgentWorkflowCatalogFormatter() {}

    public static String format(List<SkillRegistryDescriptor> skillDescriptors, PlaybookRegistrySnapshot playbookRegistry) {
        String skillSection = SkillRegistryCatalogFormatter.format(skillDescriptors);
        String playbookSection = "";
        if (playbookRegistry != null && playbookRegistry.isLoaded()) {
            playbookSection = PlaybookCatalogFormatter.format(
                    new ArrayList<>(playbookRegistry.catalogById().values()));
        }
        if (skillSection.isEmpty()) {
            return playbookSection;
        }
        if (playbookSection.isEmpty()) {
            return skillSection;
        }
        return skillSection + "\n\n---\n\n" + playbookSection;
    }
}
