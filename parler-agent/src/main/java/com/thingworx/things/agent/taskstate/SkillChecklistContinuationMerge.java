package com.thingworx.things.agent.taskstate;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistryLoader;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;

/**
 * HITL continuation: rebuild slash + dynamic skill checklists with the same duplicate/budget discipline as live v1b.2.
 *
 * <p>Normative: {@code docs/agent/task-state.md} § v1b.2 HITL Continuity.
 */
public final class SkillChecklistContinuationMerge {

    private static final Logger LOG = Logger.getLogger(SkillChecklistContinuationMerge.class.getName());

    private SkillChecklistContinuationMerge() {}

    /**
     * Union slash-loaded skill bodies first, then each dynamic skill body in order; skip a dynamic entry on re-read
     * failure, parse failure, duplicate id against prior union, or budget violation (WARN only).
     */
    public static JSONObject unionForHitlContinuation(
            AgentThing thing, List<String> slashOrdered, List<String> dynamicOrdered) {
        if (thing == null) {
            return null;
        }
        PromptContextCacheSnapshot snap = thing.getPromptContextSnapshot();
        SkillRegistrySnapshot reg = snap != null ? snap.getSkillRegistry() : null;
        JSONObject slashBase = null;
        if (slashOrdered != null && !slashOrdered.isEmpty()) {
            try {
                slashBase = SkillChecklistParser.unionFromSlashSkills(thing, slashOrdered);
            } catch (SkillChecklistParseException e) {
                LOG.log(Level.WARNING, "[{0}] HITL continuation slash checklist union skipped: {1}",
                        new Object[] {thing.getName(), e.getMessage()});
            }
        }

        List<String> dynIds = new ArrayList<>();
        List<String> dynBodies = new ArrayList<>();
        if (dynamicOrdered != null) {
            for (String dynId : dynamicOrdered) {
                if (dynId == null || dynId.isBlank()) {
                    continue;
                }
                String trimmed = dynId.trim();
                String body;
                try {
                    body = SkillRegistryLoader.loadBody(thing, reg, trimmed);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "[{0}] HITL continuation dynamic skill re-read failed id={1}: {2}",
                            new Object[] {thing.getName(), trimmed, e.getMessage()});
                    dynIds.add(trimmed);
                    dynBodies.add(null);
                    continue;
                }
                dynIds.add(trimmed);
                dynBodies.add(body != null ? body : "");
            }
        }
        return SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                slashBase, dynIds, dynBodies, thing.getName());
    }
}
