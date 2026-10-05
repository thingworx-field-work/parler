package com.thingworx.things.agent.playbook;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.thingworx.things.agent.time.ParlerTimeResolution;
import com.thingworx.things.agent.time.ParlerTimeResolver;

/**
 * {@code resolve_time_window_for_playbook} derive op (topic {@code playbook-34-35} Slice D).
 */
final class PlaybookTimeWindowDeriveOps {

    private static final List<String> DEFAULT_MATCH_FIELDS = List.of("name", "Name", "displayName", "DisplayName");
    private static final List<String> DEFAULT_UID_FIELDS = List.of("QuickTimeIntervalUID", "UID", "uid");

    private PlaybookTimeWindowDeriveOps() {}

    static JSONObject resolveTimeWindowForPlaybook(JSONObject args, PlaybookRunContext ctx)
            throws PlaybookRunException {
        if (args == null) {
            throw new PlaybookRunException("resolve_time_window_for_playbook: missing args");
        }
        String phrase = stringArg(args, "phrase", ctx).trim();
        String defaultName = args.optString("defaultQuickIntervalName", "").trim();
        String onUnsupported = args.optString("onUnsupported", "clarify").trim();
        if (!"clarify".equals(onUnsupported) && !"gap".equals(onUnsupported)) {
            throw new PlaybookRunException("resolve_time_window_for_playbook: onUnsupported must be clarify or gap");
        }
        Object rowsObj = PlaybookExpressionResolver.resolve(args.opt("quickIntervalRows"), ctx);
        JSONArray quickRows = PlaybookGenericRowArrays.coerceResolvedRowContainerToArray(rowsObj,
                "resolve_time_window_for_playbook");
        List<String> matchFields = stringListArg(args, "matchFields", DEFAULT_MATCH_FIELDS);
        List<String> uidFields = stringListArg(args, "uidFields", DEFAULT_UID_FIELDS);

        String matchPhrase = phrase;
        boolean defaulted = false;
        if (matchPhrase.isEmpty() && !defaultName.isEmpty()) {
            matchPhrase = defaultName;
            defaulted = true;
        }

        if (!matchPhrase.isEmpty() && quickRows.length() > 0) {
            JSONObject hit = matchQuickIntervalRow(quickRows, matchPhrase, matchFields);
            if (hit != null) {
                return quickIntervalResult(hit, matchPhrase, uidFields, onUnsupported, defaulted);
            }
            // No quick-interval row — fall through to explicit-range resolution (including defaulted Today).
        }

        if (matchPhrase.isEmpty()) {
            return unsupported(onUnsupported, "Time window phrase is required when no default quick interval applies.");
        }

        ZoneId zone = parseTimezone(args, ctx);
        Instant nowUtc = Instant.now();
        return resolveExplicitRange(matchPhrase, nowUtc, zone, onUnsupported, defaulted);
    }

    private static JSONObject quickIntervalResult(JSONObject hit, String matchPhrase, List<String> uidFields,
            String onUnsupported, boolean defaulted) throws PlaybookRunException {
        Number uid = extractUid(hit, uidFields);
        if (uid == null) {
            return unsupported(onUnsupported, "Matched quick interval row but no UID field found for \""
                    + matchPhrase + "\".");
        }
        String label = cellString(hit, "displayName");
        if (label.isEmpty()) {
            label = cellString(hit, "DisplayName");
        }
        if (label.isEmpty()) {
            label = cellString(hit, "name");
        }
        if (label.isEmpty()) {
            label = cellString(hit, "Name");
        }
        if (label.isEmpty()) {
            label = matchPhrase;
        }
        JSONObject output = new JSONObject()
                .put("mode", "quickInterval")
                .put("QuickTimeIntervalUID", uid)
                .put("label", label)
                .put("defaulted", defaulted);
        JSONObject out = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(
                "resolve_time_window_for_playbook: quickInterval \"" + label + "\" uid=" + uid
                        + (defaulted ? " (defaulted)" : "")));
        return out;
    }

    private static JSONObject resolveExplicitRange(String matchPhrase, Instant nowUtc, ZoneId zone,
            String onUnsupported, boolean defaulted) {
        ParlerTimeResolution cal = ParlerTimeResolver.tryResolveLocalCalendarDayEnglish(matchPhrase, nowUtc, zone);
        if (cal != null) {
            if (!cal.isSuccess()) {
                return timeFailure(onUnsupported, cal);
            }
            ParlerTimeResolution residue = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue(
                    matchPhrase);
            if (residue != null) {
                return timeFailure(onUnsupported, residue);
            }
            return explicitRangeResult(cal, zone, defaulted);
        }
        ParlerTimeResolution residue = ParlerTimeResolver.rejectIfLocalCalendarDayPhraseHasUnsupportedResidue(
                matchPhrase);
        if (residue != null) {
            return timeFailure(onUnsupported, residue);
        }
        ParlerTimeResolution rej = ParlerTimeResolver.rejectIfUnsupportedCalendarPhrase(matchPhrase);
        if (rej != null) {
            return timeFailure(onUnsupported, rej);
        }
        cal = ParlerTimeResolver.resolveRelativeDurationClosedOpen(nowUtc, matchPhrase, true, null);
        if (cal != null && cal.isSuccess()) {
            return explicitRangeResult(cal, zone, defaulted);
        }
        if (cal != null) {
            return timeFailure(onUnsupported, cal);
        }
        return unsupported(onUnsupported, "Unsupported time phrase \"" + matchPhrase + "\".");
    }

    private static JSONObject explicitRangeResult(ParlerTimeResolution cal, ZoneId zone, boolean defaulted) {
        JSONObject output = new JSONObject()
                .put("mode", "explicitRange")
                .put("StartTime", DateTimeFormatter.ISO_INSTANT.format(cal.getStartUtc()))
                .put("EndTime", DateTimeFormatter.ISO_INSTANT.format(cal.getEndUtc()))
                .put("timezone", zone.getId())
                .put("defaulted", defaulted);
        JSONObject out = new JSONObject().put("status", "ok").put("output", output);
        PlaybookNodeEvidence.attachLinesOnly(out, PlaybookNodeEvidence.singleLine(
                "resolve_time_window_for_playbook: explicitRange " + output.getString("StartTime") + " .. "
                        + output.getString("EndTime") + (defaulted ? " (defaulted)" : "")));
        return out;
    }

    private static JSONObject matchQuickIntervalRow(JSONArray rows, String phrase, List<String> matchFields) {
        String normPhrase = normalizeMatch(phrase);
        JSONObject caseHit = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            for (String field : matchFields) {
                String cell = cellString(row, field);
                if (cell.isEmpty()) {
                    continue;
                }
                if (cell.equals(phrase)) {
                    return row;
                }
                if (cell.equalsIgnoreCase(phrase)) {
                    caseHit = row;
                }
                if (normalizeMatch(cell).equals(normPhrase)) {
                    return row;
                }
            }
        }
        return caseHit;
    }

    private static Number extractUid(JSONObject row, List<String> uidFields) {
        for (String field : uidFields) {
            if (!row.has(field) || row.isNull(field)) {
                continue;
            }
            Object v = row.get(field);
            if (v instanceof Number) {
                return (Number) v;
            }
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException ignored) {
                // try next field
            }
        }
        return null;
    }

    private static ZoneId parseTimezone(JSONObject args, PlaybookRunContext ctx) throws PlaybookRunException {
        Object tzObj = args.opt("timezone");
        if (tzObj == null) {
            throw new PlaybookRunException("resolve_time_window_for_playbook: timezone is required");
        }
        Object resolved = PlaybookExpressionResolver.resolve(tzObj, ctx);
        String tz = resolved == null ? "" : String.valueOf(resolved).trim();
        if (tz.isEmpty()) {
            throw new PlaybookRunException("resolve_time_window_for_playbook: timezone must be non-blank");
        }
        try {
            return ZoneId.of(tz);
        } catch (Exception e) {
            throw new PlaybookRunException("resolve_time_window_for_playbook: invalid timezone \"" + tz + "\"");
        }
    }

    private static JSONObject unsupported(String onUnsupported, String message) {
        if ("gap".equals(onUnsupported)) {
            JSONObject output = new JSONObject().put("gaps", new JSONArray().put(PlaybookGapObjects.structured(
                    "unsupported_time_phrase", message)));
            return new JSONObject().put("status", "ok").put("output", output);
        }
        return needsClarification(message);
    }

    private static JSONObject timeFailure(String onUnsupported, ParlerTimeResolution resolution) {
        String msg = resolution.getErrorMessage() != null ? resolution.getErrorMessage() : "Time resolution failed.";
        if ("gap".equals(onUnsupported)) {
            String gapCode = resolution.getErrorCode() != null ? resolution.getErrorCode().name()
                    : "time_resolution_failed";
            JSONObject output = new JSONObject().put("gaps", new JSONArray().put(PlaybookGapObjects.structured(gapCode, msg)));
            return new JSONObject().put("status", "ok").put("output", output);
        }
        return needsClarification(msg);
    }

    private static JSONObject needsClarification(String message) {
        return new JSONObject().put("status", "needs_clarification").put("message", message);
    }

    private static String stringArg(JSONObject args, String key, PlaybookRunContext ctx) throws PlaybookRunException {
        Object v = PlaybookExpressionResolver.resolve(args.opt(key), ctx);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    private static List<String> stringListArg(JSONObject args, String key, List<String> defaults) {
        JSONArray arr = args.optJSONArray(key);
        if (arr == null || arr.length() == 0) {
            return defaults;
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "").trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? defaults : out;
    }

    private static String cellString(JSONObject row, String field) {
        if (row == null || !row.has(field) || row.isNull(field)) {
            return "";
        }
        return String.valueOf(row.get(field)).trim();
    }

    private static String normalizeMatch(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }
}
