package com.thingworx.things.agent;

import com.thingworx.thingtemplates.ThingTemplate;
import com.thingworx.things.Thing;

/**
 * Shared ThingWorx template-chain checks (e.g. {@code AIAgent} on {@link AgentThing}).
 */
public final class ThingTemplateSupport {

    private ThingTemplateSupport() {}

    /**
     * Ensures {@code thing}'s template chain includes {@code requiredBaseTemplateName} (see
     * {@link ThingTemplate#isDerivedFromTemplate(String)}).
     */
    public static void ensureThingDerivedFromTemplate(Thing thing, String requiredBaseTemplateName, String messagePrefix)
            throws Exception {
        if (thing == null) {
            throw new Exception(messagePrefix + ": Thing reference is null.");
        }
        if (requiredBaseTemplateName == null || requiredBaseTemplateName.isEmpty()) {
            throw new Exception(messagePrefix + ": required template name is empty.");
        }
        ThingTemplate tt = thing.getThingTemplate();
        if (tt == null) {
            throw new Exception(messagePrefix + ": Thing \"" + thing.getName() + "\" has no Thing template.");
        }
        if (!tt.isDerivedFromTemplate(requiredBaseTemplateName)) {
            throw new Exception(messagePrefix + ": Thing \"" + thing.getName() + "\" uses template \"" + tt.getName()
                    + "\"; must be derived from \"" + requiredBaseTemplateName + "\".");
        }
    }
}
