package com.thingworx.things.agent.hostcontext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Renders {@link HostContextTemplate} lines with {@code context.*} and {@code format.*} placeholders.
 */
public final class HostContextTemplateRenderer {

    private static final Pattern WHOLE_FORMATTER =
            Pattern.compile("^\\{\\{format\\.(\\w+)\\((.*)\\)\\}\\}$");
    private static final Pattern INLINE_FORMATTER =
            Pattern.compile("\\{\\{format\\.(\\w+)\\((.*)\\)\\}\\}");

    public static final class RenderResult {
        public final String rendered;
        public final List<String> diagnostics;
        public final boolean truncated;

        RenderResult(String rendered, List<String> diagnostics, boolean truncated) {
            this.rendered = rendered != null ? rendered : "";
            this.diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
            this.truncated = truncated;
        }
    }

    private HostContextTemplateRenderer() {
    }

    /**
     * @return error if template line invalid at load time; {@code null} if OK
     */
    public static String validateTemplateLine(String line, List<String> seenBlockNames) {
        if (line == null) {
            return "line is null";
        }
        String trimmed = line.trim();
        Matcher whole = WHOLE_FORMATTER.matcher(trimmed);
        if (whole.matches()) {
            String fn = whole.group(1);
            List<String> args = splitFormatterArgs(whole.group(2));
            String err = HostContextFormatterSpec.validateCall(fn, args.size(), true);
            if (err != null) {
                return err;
            }
            if ("jsonFence".equals(fn)) {
                String blockName = unquote(args.get(1));
                String bnErr = HostContextBlockNameValidator.validateOrReason(blockName);
                if (bnErr != null) {
                    return bnErr;
                }
                if (seenBlockNames.contains(blockName)) {
                    return "duplicate blockName: " + blockName;
                }
                seenBlockNames.add(blockName);
            }
            return null;
        }
        if (line.contains("{{format.jsonFence")) {
            return "format.jsonFence placeholder must occupy the whole line (no inline jsonFence)";
        }
        Matcher inline = INLINE_FORMATTER.matcher(line);
        while (inline.find()) {
            String fn = inline.group(1);
            List<String> args = splitFormatterArgs(inline.group(2));
            String err = HostContextFormatterSpec.validateCall(fn, args.size(), false);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    public static RenderResult render(HostContextTemplate template, JSONObject context) {
        List<String> diagnostics = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        for (String line : template.promptTemplateLines()) {
            if (line == null) {
                sb.append('\n');
                continue;
            }
            String trimmed = line.trim();
            Matcher whole = WHOLE_FORMATTER.matcher(trimmed);
            if (whole.matches()) {
                String fn = whole.group(1);
                List<String> args = splitFormatterArgs(whole.group(2));
                String formatted = invokeFormatter(fn, args, context, diagnostics);
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append('\n');
                }
                sb.append(formatted).append('\n');
                continue;
            }
            String expanded = expandInlineFormatters(line, context, diagnostics);
            String replaced = replaceContextVars(expanded, context, diagnostics);
            sb.append(replaced).append('\n');
        }
        String out = sb.toString().trim();
        if (out.length() > template.maxRenderedChars()) {
            out = truncatePreservingFences(out, template.maxRenderedChars());
            truncated = true;
            diagnostics.add("rendered output truncated at maxRenderedChars=" + template.maxRenderedChars());
        }
        return new RenderResult(out, diagnostics, truncated);
    }

    /**
     * Truncate rendered output without leaving an unclosed markdown fence.
     */
    static String truncatePreservingFences(String out, int maxChars) {
        if (out.length() <= maxChars) {
            return out;
        }
        String cut = out.substring(0, maxChars);
        int open = countUnclosedFenceMarkers(cut);
        StringBuilder sb = new StringBuilder(cut);
        for (int i = 0; i < open; i++) {
            sb.append("\n```");
        }
        sb.append("\n… [truncated]");
        return sb.toString();
    }

    /** Count ``` fence markers on their own line that are not yet closed. */
    static int countUnclosedFenceMarkers(String text) {
        int open = 0;
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) {
                open = (open + 1) % 2;
            }
        }
        return open;
    }

    private static String invokeFormatter(
            String name,
            List<String> args,
            JSONObject context,
            List<String> diagnostics) {
        switch (name) {
            case "jsonFence": {
                if (args.size() != 2) {
                    diagnostics.add("format.jsonFence: expected 2 arguments");
                    return HostContextFormatters.UNAVAILABLE;
                }
                Object val = resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.jsonFence(val, unquote(args.get(1)), diagnostics);
            }
            case "typedList": {
                if (args.size() != 4) {
                    diagnostics.add("format.typedList: expected 4 arguments");
                    return HostContextFormatters.UNAVAILABLE;
                }
                Object val = resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.typedList(val, unquote(args.get(1)), unquote(args.get(2)),
                        unquote(args.get(3)), diagnostics);
            }
            case "filters": {
                Object val = args.isEmpty() ? null : resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.filters(val, diagnostics);
            }
            case "timeWindow": {
                Object val = args.isEmpty() ? null : resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.timeWindow(val, diagnostics);
            }
            case "hierarchy": {
                Object net = args.size() > 0 ? resolveContextPath(context, args.get(0).trim()) : null;
                Object node = args.size() > 1 ? resolveContextPath(context, args.get(1).trim()) : null;
                return HostContextFormatters.hierarchy(net, node, diagnostics);
            }
            case "list": {
                if (args.size() != 2) {
                    diagnostics.add("format.list: expected 2 arguments");
                    return HostContextFormatters.UNAVAILABLE;
                }
                Object val = resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.list(val, unquote(args.get(1)), diagnostics);
            }
            case "kv": {
                Object val = args.isEmpty() ? null : resolveContextPath(context, args.get(0).trim());
                return HostContextFormatters.kv(val, diagnostics);
            }
            default:
                diagnostics.add("unknown formatter: " + name);
                return HostContextFormatters.UNAVAILABLE;
        }
    }

    static Object resolveContextPath(JSONObject context, String pathExpr) {
        if (context == null || pathExpr == null || pathExpr.isEmpty()) {
            return null;
        }
        String path = pathExpr.startsWith("context.") ? pathExpr.substring("context.".length()) : pathExpr;
        String[] parts = path.split("\\.");
        Object cur = context;
        for (String p : parts) {
            if (!(cur instanceof JSONObject)) {
                return null;
            }
            JSONObject o = (JSONObject) cur;
            if (!o.has(p)) {
                return null;
            }
            cur = o.get(p);
        }
        return cur == JSONObject.NULL ? null : cur;
    }

    private static String expandInlineFormatters(String line, JSONObject context, List<String> diagnostics) {
        Matcher m = INLINE_FORMATTER.matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String fn = m.group(1);
            List<String> args = splitFormatterArgs(m.group(2));
            String rep = invokeFormatter(fn, args, context, diagnostics);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceContextVars(String line, JSONObject context, List<String> diagnostics) {
        Matcher m = Pattern.compile("\\{\\{context\\.([\\w.]+)\\}\\}").matcher(line);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object val = resolveContextPath(context, "context." + m.group(1));
            String rep = val != null ? String.valueOf(val) : HostContextFormatters.UNAVAILABLE;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> splitFormatterArgs(String inner) {
        List<String> out = new ArrayList<>();
        if (inner == null || inner.trim().isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        char quote = '"';
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inQuote) {
                cur.append(c);
                if (c == quote) {
                    inQuote = false;
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2
                && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
