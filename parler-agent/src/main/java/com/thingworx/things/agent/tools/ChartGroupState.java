package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * C3b-1 chart group (chart-enhancement design §8.5): one declared group per user request, its ordered
 * members, their states and the manifest revision counter. Pure {@code org.json}; lives on
 * {@link TabularChartRoundState} so an approval pause carries it. Every state change marks the group
 * dirty; the wire emitter turns a dirty group into the next full-manifest revision.
 */
public final class ChartGroupState {
    public static final int MIN_MEMBERS = 2;
    public static final int MAX_MEMBERS = 6;
    public static final int MAX_TITLE_CHARS = 120;
    public static final int MAX_NAME_CHARS = 80;
    public static final int MAX_MESSAGE_CHARS = 200;
    /** C3b-2a (design §8.7): the shared category dimension label bound. */
    public static final int MAX_DIMENSION_CHARS = 80;
    /** C3b-2a (design §8.7): the palette slot count = {@code chartSeriesSlot} modulo, the shared-key cap. */
    public static final int MAX_SHARED_KEYS = 24;
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    public static final Set<String> LAYOUTS = Set.of("auto", "stack", "grid");
    public static final Pattern MEMBER_KEY = Pattern.compile("^[a-z0-9_-]{1,32}$");
    /** Build failures that mean "nothing to draw" rather than an operational failure. */
    public static final Set<String> NO_DATA_CODES = Set.of("EMPTY_AFTER_FILTER", "HEATMAP_ALL_MISSING", "CHART_FALLBACK");
    public static final String STATE_PENDING = "pending";
    public static final String STATE_READY = "ready";
    public static final String STATE_NO_DATA = "no-data";
    public static final String STATE_ERROR = "error";
    public static final String STATE_CANCELLED = "cancelled";
    public static final String CODE_MEMBER_NOT_PRODUCED = "MEMBER_NOT_PRODUCED";
    public static final String CODE_DOWNLINK_FAILED = "DOWNLINK_FAILED";
    public static final String CODE_TURN_INCOMPLETE = "TURN_INCOMPLETE";

    public static final class Member {
        public final String key;
        public final String name;
        private String state = STATE_PENDING;
        private String chartId;
        private String code;
        private String message;
        private boolean downlinkFailed;
        /** C3b-2a (design §8.7): true when every category key this member produced is in {@link #sharedCategories}. */
        private boolean colorShared;

        Member(String key, String name) {
            this.key = key;
            this.name = name;
        }

        Member copy() {
            Member m = new Member(key, name);
            m.state = state;
            m.chartId = chartId;
            m.code = code;
            m.message = message;
            m.downlinkFailed = downlinkFailed;
            m.colorShared = colorShared;
            return m;
        }

        public String state() {
            return state;
        }

        public String chartId() {
            return chartId;
        }

        public String code() {
            return code;
        }

        public boolean colorShared() {
            return colorShared;
        }
    }

    private final String groupId;
    private final String title;
    private final String layout;
    /** C3b-2a (design §8.7): the declared shared category dimension label, or {@code null} for a plain C3b-1 group. */
    private final String sharedCategoryDimension;
    /** C3b-2a (design §8.7): shared category keys; {@code keys[i]} owns palette slot {@code i}, append-only, never reordered. */
    private final List<String> sharedCategories = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();
    private int revision;
    private boolean finalSent;
    private boolean dirty;

    public ChartGroupState(String groupId, String title, String layout, List<String[]> keysAndNames) {
        this(groupId, title, layout, keysAndNames, null);
    }

    public ChartGroupState(String groupId, String title, String layout, List<String[]> keysAndNames,
            String sharedCategoryDimension) {
        this.groupId = groupId;
        this.title = title;
        this.layout = layout;
        this.sharedCategoryDimension = sharedCategoryDimension;
        for (String[] kn : keysAndNames) {
            members.add(new Member(kn[0], kn[1]));
        }
        this.dirty = true;
    }

    private ChartGroupState(ChartGroupState other) {
        this.groupId = other.groupId;
        this.title = other.title;
        this.layout = other.layout;
        this.sharedCategoryDimension = other.sharedCategoryDimension;
        this.sharedCategories.addAll(other.sharedCategories);
        for (Member m : other.members) {
            members.add(m.copy());
        }
        this.revision = other.revision;
        this.finalSent = other.finalSent;
        this.dirty = other.dirty;
    }

    public ChartGroupState copy() {
        return new ChartGroupState(this);
    }

    public String groupId() {
        return groupId;
    }

    public String title() {
        return title;
    }

    public String layout() {
        return layout;
    }

    public String sharedCategoryDimension() {
        return sharedCategoryDimension;
    }

    public List<String> sharedCategories() {
        return List.copyOf(sharedCategories);
    }

    public int revision() {
        return revision;
    }

    public boolean isFinalSent() {
        return finalSent;
    }

    public boolean isDirty() {
        return dirty && !finalSent;
    }

    public List<Member> members() {
        return List.copyOf(members);
    }

    public Member member(String key) {
        for (Member m : members) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        return null;
    }

    /** @return {@code null} when the member can be bound, else the {@code details.reason} value. */
    public String bindReason(String key) {
        Member m = member(key);
        if (m == null) {
            return "unknown_member";
        }
        if (!STATE_PENDING.equals(m.state) || m.chartId != null) {
            return "member_not_pending";
        }
        return null;
    }

    /** A bound build succeeded: the member now owns {@code chartId} and turns {@code ready} once that chart is downlinked. */
    public void assignChartId(String key, String chartId) {
        Member m = member(key);
        if (m != null && STATE_PENDING.equals(m.state)) {
            m.chartId = chartId;
        }
    }

    /**
     * C3b-2a (design §8.7): normalise a category label — trim, collapse internal whitespace runs to one space,
     * case-sensitive; {@code null} when it folds to empty (such a label produces no key and takes no slot).
     */
    static String normalizeCategoryKey(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = WHITESPACE_RUN.matcher(String.valueOf(raw).trim()).replaceAll(" ");
        return s.isEmpty() ? null : s;
    }

    /**
     * C3b-2a (design §8.7): the ordered category keys a built {@code ChartBlock} contributes to shared colour.
     * Only classifying kinds participate: {@code pie} takes each {@code series[0].x[i]} whose {@code y[i]} is a
     * finite positive number ({@code Other} is its own key; zero/non-positive slices take no key);
     * {@code bar}/{@code line}/{@code scatter} with at least two series take each {@code series[].name}. Single-series
     * bar/line/scatter, histogram, boxplot and heatmap contribute nothing. Keys are normalised and de-duplicated
     * within the chart in first-seen order.
     */
    public static List<String> categoryKeys(JSONObject chartBlock) {
        List<String> out = new ArrayList<>();
        if (chartBlock == null) {
            return out;
        }
        String kind = chartBlock.optString("kind", "");
        JSONArray series = chartBlock.optJSONArray("series");
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if ("pie".equals(kind)) {
            if (series == null || series.length() < 1) {
                return out;
            }
            JSONObject s0 = series.optJSONObject(0);
            JSONArray x = s0 != null ? s0.optJSONArray("x") : null;
            JSONArray y = s0 != null ? s0.optJSONArray("y") : null;
            if (x == null || y == null) {
                return out;
            }
            int n = Math.min(x.length(), y.length());
            for (int i = 0; i < n; i++) {
                double v = y.optDouble(i, Double.NaN);
                if (!Double.isFinite(v) || v <= 0) {
                    continue;
                }
                String key = normalizeCategoryKey(x.opt(i));
                if (key != null && seen.add(key)) {
                    out.add(key);
                }
            }
        } else if (("bar".equals(kind) || "line".equals(kind) || "scatter".equals(kind))
                && series != null && series.length() >= 2) {
            for (int i = 0; i < series.length(); i++) {
                JSONObject s = series.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                String key = normalizeCategoryKey(s.has("name") && !s.isNull("name") ? s.opt("name") : null);
                if (key != null && seen.add(key)) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /**
     * C3b-2a (design §8.7): after a bound build succeeds, append this chart's new category keys to the shared list
     * (existing keys never move; already-assigned slots never change) and mark the member colour-shared. When the
     * group declared no dimension, or the kind contributes no keys, nothing changes. Over cap: if appending would
     * exceed {@link #MAX_SHARED_KEYS}, append nothing and leave the member on its per-chart palette
     * ({@code colorShared = false}); the member still renders.
     */
    public void bindMemberCategories(String key, JSONObject chartBlock) {
        if (sharedCategoryDimension == null) {
            return;
        }
        Member m = member(key);
        if (m == null) {
            return;
        }
        List<String> keys = categoryKeys(chartBlock);
        if (keys.isEmpty()) {
            return;
        }
        List<String> toAppend = new ArrayList<>();
        for (String k : keys) {
            if (!sharedCategories.contains(k) && !toAppend.contains(k)) {
                toAppend.add(k);
            }
        }
        if (sharedCategories.size() + toAppend.size() > MAX_SHARED_KEYS) {
            m.colorShared = false;
            return;
        }
        sharedCategories.addAll(toAppend);
        m.colorShared = true;
        dirty = true;
    }

    /** A bound build failed: {@code no-data} for the frozen empty/fallback codes, {@code error} for every other code. */
    public void failMember(String key, String code, String message) {
        Member m = member(key);
        if (m == null || !STATE_PENDING.equals(m.state)) {
            return;
        }
        m.state = NO_DATA_CODES.contains(code) ? STATE_NO_DATA : STATE_ERROR;
        m.code = code;
        m.message = message != null && message.length() > MAX_MESSAGE_CHARS ? message.substring(0, MAX_MESSAGE_CHARS) : message;
        dirty = true;
    }

    /** @return true when a pending member owning {@code chartId} became ready (a new revision is due). */
    public boolean onChartDownlinked(String chartId) {
        if (chartId == null) {
            return false;
        }
        for (Member m : members) {
            if (chartId.equals(m.chartId) && STATE_PENDING.equals(m.state)) {
                m.state = STATE_READY;
                dirty = true;
                return true;
            }
        }
        return false;
    }

    public void onChartDownlinkFailed(String chartId) {
        for (Member m : members) {
            if (chartId != null && chartId.equals(m.chartId) && STATE_PENDING.equals(m.state)) {
                m.downlinkFailed = true;
            }
        }
    }

    /** End of turn: every member still pending converges to a terminal state. */
    public void convergePending(boolean userCancelled) {
        for (Member m : members) {
            if (!STATE_PENDING.equals(m.state)) {
                continue;
            }
            if (userCancelled) {
                m.state = STATE_CANCELLED;
                m.chartId = null;
            } else {
                m.state = STATE_ERROR;
                m.code = m.downlinkFailed ? CODE_DOWNLINK_FAILED : CODE_MEMBER_NOT_PRODUCED;
                m.chartId = null;
            }
            dirty = true;
        }
    }

    /** History rebuild without the persisted final manifest: every non-terminal member is {@code TURN_INCOMPLETE}. */
    public void convergeIncomplete() {
        for (Member m : members) {
            if (STATE_PENDING.equals(m.state)) {
                m.state = STATE_ERROR;
                m.code = CODE_TURN_INCOMPLETE;
                m.chartId = null;
                dirty = true;
            }
        }
    }

    /** The next full manifest revision; {@code fin} marks it final and freezes the group. */
    public JSONObject nextManifest(boolean fin) {
        revision++;
        dirty = false;
        if (fin) {
            finalSent = true;
        }
        return manifest(fin);
    }

    private JSONObject manifest(boolean fin) {
        JSONObject o = new JSONObject();
        o.put("groupId", groupId);
        o.put("revision", revision);
        o.put("title", title);
        o.put("layout", layout);
        o.put("final", fin);
        JSONArray arr = new JSONArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ready", 0);
        counts.put("noData", 0);
        counts.put("error", 0);
        counts.put("cancelled", 0);
        int order = 0;
        for (Member m : members) {
            JSONObject j = new JSONObject();
            j.put("key", m.key);
            j.put("order", order++);
            j.put("name", m.name);
            j.put("expectedType", "chart");
            j.put("state", m.state);
            if (STATE_READY.equals(m.state) && m.chartId != null) {
                j.put("chartId", m.chartId);
                counts.merge("ready", 1, Integer::sum);
            } else if (STATE_NO_DATA.equals(m.state)) {
                counts.merge("noData", 1, Integer::sum);
            } else if (STATE_ERROR.equals(m.state)) {
                counts.merge("error", 1, Integer::sum);
            } else if (STATE_CANCELLED.equals(m.state)) {
                counts.merge("cancelled", 1, Integer::sum);
            }
            if ((STATE_NO_DATA.equals(m.state) || STATE_ERROR.equals(m.state)) && m.code != null) {
                j.put("code", m.code);
                if (m.message != null && !m.message.isBlank()) {
                    j.put("message", m.message);
                }
            }
            if (m.colorShared) {
                j.put("colorShared", true);
            }
            arr.put(j);
        }
        o.put("members", arr);
        if (sharedCategoryDimension != null) {
            JSONObject shared = new JSONObject();
            shared.put("dimension", sharedCategoryDimension);
            shared.put("keys", new JSONArray(sharedCategories));
            o.put("sharedCategories", shared);
        }
        JSONObject summary = new JSONObject();
        summary.put("expected", members.size());
        summary.put("ready", counts.get("ready"));
        summary.put("noData", counts.get("noData"));
        summary.put("error", counts.get("error"));
        summary.put("cancelled", counts.get("cancelled"));
        summary.put("final", fin);
        o.put("summary", summary);
        return o;
    }
}
