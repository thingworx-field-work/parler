package com.thingworx.things.agent.taskstate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.skillregistry.SkillRegistryLoader;
import com.thingworx.things.agent.skillregistry.SkillRegistrySnapshot;

/**
 * Extracts and validates {@code ```parler-task-checklist-v1} fenced JSON from skill markdown (v1b).
 * Normative: {@code docs/agent/task-state.md} § v1b Skill Checklist Contract.
 */
public final class SkillChecklistParser {

    private static final Logger LOG = Logger.getLogger(SkillChecklistParser.class.getName());

    public static final int MAX_SKILL_ITEMS = 30;

    private static final Pattern CHECKLIST_FENCE = Pattern.compile(
            "```parler-task-checklist-v1\\s*\\r?\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private SkillChecklistParser() {}

    /** Counts {@code ```parler-task-checklist-v1} fenced blocks (v1b.2 live + continuation discipline). */
    public static int countChecklistFences(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return 0;
        }
        Matcher m = CHECKLIST_FENCE.matcher(markdown);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    /**
     * Parses exactly one checklist fence; throws when none or more than one fence is present.
     */
    public static JSONObject parseExactlyOneChecklistFence(String markdown) throws SkillChecklistParseException {
        if (markdown == null || markdown.isEmpty()) {
            throw new SkillChecklistParseException("no checklist fence");
        }
        Matcher m = CHECKLIST_FENCE.matcher(markdown);
        if (!m.find()) {
            throw new SkillChecklistParseException("no checklist fence");
        }
        String inner = m.group(1).trim();
        if (m.find()) {
            throw new SkillChecklistParseException("multiple checklist fences");
        }
        return parseOneFence(inner);
    }

    /**
     * Concatenates {@code requiredEvidence} from every fence across bodies in order; fails fast on duplicate {@code id}.
     */
    public static JSONObject unionChecklists(List<String> markdownBodies) throws SkillChecklistParseException {
        if (markdownBodies == null || markdownBodies.isEmpty()) {
            return null;
        }
        JSONArray union = new JSONArray();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        String title = null;
        for (String md : markdownBodies) {
            if (md == null || md.isEmpty()) {
                continue;
            }
            Matcher m = CHECKLIST_FENCE.matcher(md);
            while (m.find()) {
                String jsonBlock = m.group(1).trim();
                JSONObject root = parseOneFence(jsonBlock);
                if (root.has("title") && !root.isNull("title")) {
                    String t = root.optString("title", "").trim();
                    if (!t.isEmpty() && title == null) {
                        title = t;
                    }
                }
                JSONArray req = root.getJSONArray("requiredEvidence");
                for (int i = 0; i < req.length(); i++) {
                    JSONObject item = req.getJSONObject(i);
                    String id = item.getString("id");
                    if (seenIds.contains(id)) {
                        throw new SkillChecklistParseException("duplicate checklist id: " + id);
                    }
                    seenIds.add(id);
                    union.put(item);
                }
            }
        }
        if (union.length() == 0) {
            return null;
        }
        if (union.length() > MAX_SKILL_ITEMS) {
            throw new SkillChecklistParseException(
                    "checklist union exceeds maxSkillItems (" + MAX_SKILL_ITEMS + "): " + union.length());
        }
        JSONObject out = new JSONObject();
        out.put("schemaVersion", 1);
        if (title != null) {
            out.put("title", title);
        }
        out.put("requiredEvidence", union);
        return out;
    }

    private static JSONObject parseOneFence(String jsonBlock) throws SkillChecklistParseException {
        JSONObject root;
        try {
            root = new JSONObject(jsonBlock);
        } catch (Exception e) {
            throw new SkillChecklistParseException("invalid checklist JSON: " + e.getMessage());
        }
        Iterator<String> topKeys = root.keys();
        while (topKeys.hasNext()) {
            String k = topKeys.next();
            if (!"schemaVersion".equals(k) && !"title".equals(k) && !"requiredEvidence".equals(k)) {
                throw new SkillChecklistParseException("unknown top-level key: " + k);
            }
        }
        if (root.optInt("schemaVersion", -1) != 1) {
            throw new SkillChecklistParseException("schemaVersion must be 1");
        }
        if (!root.has("requiredEvidence")) {
            throw new SkillChecklistParseException("requiredEvidence required");
        }
        JSONArray arr = root.getJSONArray("requiredEvidence");
        JSONArray normalized = new JSONArray();
        Set<String> idsInFence = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            validateItemKeys(item);
            String id = item.optString("id", "").trim();
            String desc = item.optString("description", "").trim();
            if (id.isEmpty() || desc.isEmpty()) {
                throw new SkillChecklistParseException("each item requires id and description");
            }
            if (idsInFence.contains(id)) {
                throw new SkillChecklistParseException("duplicate checklist id in fence: " + id);
            }
            idsInFence.add(id);
            String kind = item.optString("kind", "").trim();
            boolean hasTool = item.has("tool") && !item.isNull("tool")
                    && !item.optString("tool", "").trim().isEmpty();
            if (kind.isEmpty()) {
                kind = hasTool ? "evidence" : "guidance";
                try {
                    item.put("kind", kind);
                } catch (Exception ignored) {
                    // defensive
                }
            }
            if (!"evidence".equals(kind) && !"guidance".equals(kind) && !"synthesis".equals(kind)) {
                throw new SkillChecklistParseException("invalid kind for id=" + id + ": " + kind);
            }
            if ("evidence".equals(kind) && !hasTool) {
                throw new SkillChecklistParseException("kind=evidence requires tool for id=" + id);
            }
            if (("guidance".equals(kind) || "synthesis".equals(kind)) && hasTool) {
                throw new SkillChecklistParseException("guidance/synthesis must not include tool for id=" + id);
            }
            validateCardinality(item, id);
            if (item.has("match") && !item.isNull("match")) {
                validateMatchObject(item.getJSONObject("match"), id);
            }
            normalized.put(item);
        }
        JSONObject copy = new JSONObject();
        copy.put("schemaVersion", 1);
        if (root.has("title") && !root.isNull("title")) {
            copy.put("title", root.optString("title", ""));
        }
        copy.put("requiredEvidence", normalized);
        return copy;
    }

    private static final String[] ITEM_KEYS_ALLOWED =
            {"id", "kind", "tool", "description", "cardinality", "match"};

    private static final String[] MATCH_KEYS_ALLOWED =
            {"targetType", "targetName", "operation", "resultKind"};

    private static void validateCardinality(JSONObject item, String id) throws SkillChecklistParseException {
        if (!item.has("cardinality") || item.isNull("cardinality")) {
            return;
        }
        String c = item.optString("cardinality", "").trim();
        if (!"one".equals(c) && !"one_or_more".equals(c)) {
            throw new SkillChecklistParseException("invalid cardinality for id=" + id + ": " + c);
        }
    }

    private static void validateMatchObject(JSONObject match, String itemId) throws SkillChecklistParseException {
        if (match == null) {
            return;
        }
        Iterator<String> keys = match.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            boolean ok = false;
            for (String a : MATCH_KEYS_ALLOWED) {
                if (a.equals(k)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new SkillChecklistParseException("unknown match key for id=" + itemId + ": " + k);
            }
        }
    }

    private static void validateItemKeys(JSONObject item) throws SkillChecklistParseException {
        Iterator<String> keys = item.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            boolean ok = false;
            for (String a : ITEM_KEYS_ALLOWED) {
                if (a.equals(k)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new SkillChecklistParseException("unknown checklist item key: " + k);
            }
        }
    }

    /**
     * Loads skill bodies via {@link SkillRegistryLoader} and unions embedded checklists.
     */
    public static JSONObject unionFromSlashSkills(com.thingworx.things.agent.AgentThing thing, List<String> skillShortIds)
            throws SkillChecklistParseException {
        if (skillShortIds == null || skillShortIds.isEmpty()) {
            return null;
        }
        PromptContextCacheSnapshot snap = thing != null ? thing.getPromptContextSnapshot() : null;
        SkillRegistrySnapshot reg = snap != null ? snap.getSkillRegistry() : null;
        List<String> bodies = new ArrayList<>();
        for (String id : skillShortIds) {
            try {
                String body = SkillRegistryLoader.loadBody(thing, reg, id);
                if (body != null && !body.isEmpty()) {
                    bodies.add(body);
                }
            } catch (Exception e) {
                String agentName = thing != null ? thing.getName() : "?";
                LOG.log(Level.WARNING, "[{0}] slash skill checklist union: could not load body for id={1}: {2}",
                        new Object[] {agentName, id, e.getMessage()});
            }
        }
        return unionChecklists(bodies);
    }
}
