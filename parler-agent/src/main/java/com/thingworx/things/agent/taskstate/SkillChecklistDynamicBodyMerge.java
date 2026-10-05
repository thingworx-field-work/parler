package com.thingworx.things.agent.taskstate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure v1b.2 checklist union: append dynamic skill markdown bodies to an existing slash union JSON.
 *
 * <p>Extracted for unit tests without wiring {@link com.thingworx.things.agent.AgentThing}. Production continuation
 * calls this after fetching bodies.
 */
public final class SkillChecklistDynamicBodyMerge {

    private static final Logger LOG = Logger.getLogger(SkillChecklistDynamicBodyMerge.class.getName());

    private SkillChecklistDynamicBodyMerge() {}

    /**
     * Merges dynamic skill bodies in lockstep with {@code dynamicIdsForLog} (same length). Skips blank ids. For each
     * pair, skips null body (treated as re-read failure), empty body with no fences, multi-fence, parse errors,
     * duplicate evidence ids against prior union, or budget.
     *
     * @param slashUnionOrNull checklist JSON from {@link SkillChecklistParser#unionFromSlashSkills} or {@code null}
     * @param dynamicIdsForLog stable short ids (trimmed) for logging; may contain blanks (skipped)
     * @param markdownBodies parallel to non-skipped entries — caller must pass one body per id in {@code
     *     dynamicIdsForLog} after the same filtering order as {@link SkillChecklistContinuationMerge} uses when
     *     building lists
     * @param agentNameForLog when non-null, WARN/INFO logs use this prefix; when {@code null}, logging is suppressed
     *     (unit tests)
     * @throws IllegalArgumentException when {@code dynamicIdsForLog.size() != markdownBodies.size()}
     */
    public static JSONObject mergeDynamicBodiesIntoSlashUnion(
            JSONObject slashUnionOrNull,
            List<String> dynamicIdsForLog,
            List<String> markdownBodies,
            String agentNameForLog) {
        if (dynamicIdsForLog == null || markdownBodies == null) {
            throw new IllegalArgumentException("dynamicIdsForLog and markdownBodies are required");
        }
        if (dynamicIdsForLog.size() != markdownBodies.size()) {
            throw new IllegalArgumentException(
                    "dynamicIdsForLog and markdownBodies must have the same size: "
                            + dynamicIdsForLog.size() + " vs " + markdownBodies.size());
        }

        JSONArray unionArr = new JSONArray();
        Set<String> seenIds = new HashSet<>();
        String title = null;

        if (slashUnionOrNull != null && slashUnionOrNull.has("requiredEvidence")) {
            JSONArray req = slashUnionOrNull.getJSONArray("requiredEvidence");
            for (int i = 0; i < req.length(); i++) {
                JSONObject item = req.getJSONObject(i);
                unionArr.put(new JSONObject(item.toString()));
                seenIds.add(item.getString("id"));
            }
            if (slashUnionOrNull.has("title") && !slashUnionOrNull.isNull("title")) {
                String t = slashUnionOrNull.optString("title", "").trim();
                if (!t.isEmpty()) {
                    title = t;
                }
            }
        }

        for (int idx = 0; idx < dynamicIdsForLog.size(); idx++) {
            String dynId = dynamicIdsForLog.get(idx);
            if (dynId == null || dynId.isBlank()) {
                continue;
            }
            String trimmed = dynId.trim();
            String body = markdownBodies.get(idx);
            if (body == null) {
                logWarn(agentNameForLog, "HITL continuation dynamic skill re-read failed id={0} (null body)",
                        trimmed);
                continue;
            }
            int fc = SkillChecklistParser.countChecklistFences(body);
            if (fc == 0) {
                continue;
            }
            if (fc > 1) {
                logWarn(agentNameForLog,
                        "HITL continuation dynamic skill id={0}: multiple checklist fences skipped", trimmed);
                continue;
            }
            JSONObject parsed;
            try {
                parsed = SkillChecklistParser.parseExactlyOneChecklistFence(body);
            } catch (SkillChecklistParseException e) {
                logWarn(agentNameForLog, "HITL continuation dynamic skill id={0}: {1}", trimmed, e.getMessage());
                continue;
            }
            JSONArray req = parsed.getJSONArray("requiredEvidence");
            boolean collision = false;
            for (int j = 0; j < req.length(); j++) {
                String id = req.getJSONObject(j).getString("id");
                if (seenIds.contains(id)) {
                    logWarn(agentNameForLog,
                            "HITL continuation dynamic skill id={0}: duplicate checklist id={1} skipped",
                            trimmed, id);
                    collision = true;
                    break;
                }
            }
            if (collision) {
                continue;
            }
            if (unionArr.length() + req.length() > SkillChecklistParser.MAX_SKILL_ITEMS) {
                logWarn(agentNameForLog,
                        "HITL continuation dynamic skill id={0}: budget exceeded (would be {1} items)",
                        trimmed, unionArr.length() + req.length());
                continue;
            }
            for (int j = 0; j < req.length(); j++) {
                JSONObject item = req.getJSONObject(j);
                unionArr.put(new JSONObject(item.toString()));
                seenIds.add(item.getString("id"));
            }
            if (title == null && parsed.has("title") && !parsed.isNull("title")) {
                String t = parsed.optString("title", "").trim();
                if (!t.isEmpty()) {
                    title = t;
                }
            }
        }

        if (unionArr.length() == 0) {
            return null;
        }
        JSONObject out = new JSONObject();
        out.put("schemaVersion", 1);
        if (title != null && !title.isEmpty()) {
            out.put("title", title);
        }
        out.put("requiredEvidence", unionArr);
        return out;
    }

    private static void logWarn(String agentNameForLog, String pattern, Object... args) {
        if (agentNameForLog == null) {
            return;
        }
        String msg = "[" + agentNameForLog + "] " + formatPattern(pattern, args);
        LOG.log(Level.WARNING, msg);
    }

    private static String formatPattern(String pattern, Object... args) {
        String out = pattern;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }
}
