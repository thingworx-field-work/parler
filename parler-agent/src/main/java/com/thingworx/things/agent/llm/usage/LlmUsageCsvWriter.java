package com.thingworx.things.agent.llm.usage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * RFC 4180 CSV export for helper reports (CC-7.6).
 */
public final class LlmUsageCsvWriter {

    public static final int MAX_CSV_CHARS = 5_000_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> FIXED_COLUMNS = List.of(
            "callId",
            "logicalCallId",
            "conversationId",
            "agentThing",
            "requestedModel",
            "responseModel",
            "providerFamily",
            "providerThingName",
            "apiShapeId",
            "callKind",
            "callStartedAt",
            "endedAt",
            "outcome",
            "dispatchState",
            "cancelObservedAt",
            "usageStatus",
            "conflict",
            "costStatus",
            "knownCostUsd",
            "priceVersion",
            "captureCoverage",
            "reportAsOf");

    static final List<String> OPENAI_VENDOR_COLUMNS = List.of(
            "openai/prompt_tokens",
            "openai/completion_tokens",
            "openai/total_tokens",
            "openai/prompt_tokens_details/cached_tokens",
            "openai/prompt_tokens_details/audio_tokens",
            "openai/completion_tokens_details/reasoning_tokens",
            "openai/completion_tokens_details/audio_tokens",
            "openai/completion_tokens_details/accepted_prediction_tokens",
            "openai/completion_tokens_details/rejected_prediction_tokens");

    static final List<String> ANTHROPIC_VENDOR_COLUMNS = List.of(
            "anthropic/input_tokens",
            "anthropic/output_tokens",
            "anthropic/cache_read_input_tokens",
            "anthropic/cache_creation_input_tokens",
            "anthropic/cache_creation/ephemeral_5m_input_tokens",
            "anthropic/cache_creation/ephemeral_1h_input_tokens",
            "anthropic/output_tokens_details/thinking_tokens");

    private static final List<String> VENDOR_COLUMNS = buildVendorColumns();

    private static List<String> buildVendorColumns() {
        List<String> columns = new ArrayList<>(OPENAI_VENDOR_COLUMNS.size() + ANTHROPIC_VENDOR_COLUMNS.size() + 1);
        columns.addAll(OPENAI_VENDOR_COLUMNS);
        columns.addAll(ANTHROPIC_VENDOR_COLUMNS);
        columns.add("rawUsageJson");
        return List.copyOf(columns);
    }

    private LlmUsageCsvWriter() {}

    public static String write(ObjectNode report) throws LlmUsageReportException {
        if (report == null) {
            throw new LlmUsageReportException("REPORT_READ_FAILED", "missing report");
        }
        ArrayNode calls = report.withArray("calls");
        Set<String> extensionColumns = new TreeSet<>();
        for (int i = 0; i < calls.size(); i++) {
            ObjectNode call = (ObjectNode) calls.get(i);
            JsonNode rawUsage = call.path("rawUsage");
            if (rawUsage.isObject()) {
                collectNumericLeafPaths(rawUsage, "", extensionColumns);
            }
        }
        List<String> columns = new ArrayList<>(FIXED_COLUMNS.size() + VENDOR_COLUMNS.size() + extensionColumns.size());
        columns.addAll(FIXED_COLUMNS);
        columns.addAll(VENDOR_COLUMNS);
        columns.addAll(extensionColumns);

        StringBuilder sb = new StringBuilder();
        appendRow(sb, columns);
        String captureCoverage = report.path("captureCoverage").asText("observed_only");
        String reportAsOf = report.path("reportAsOf").asText("");
        String priceVersion = report.path("priceVersion").asText("");
        for (int i = 0; i < calls.size(); i++) {
            ObjectNode call = (ObjectNode) calls.get(i);
            List<String> row = new ArrayList<>(columns.size());
            for (String column : columns) {
                row.add(cellValue(call, column, captureCoverage, reportAsOf, priceVersion, extensionColumns));
            }
            appendRow(sb, row);
        }
        if (sb.length() > MAX_CSV_CHARS) {
            throw new LlmUsageReportException("REPORT_LIMIT_EXCEEDED", "CSV exceeds max size");
        }
        return sb.toString();
    }

    private static String cellValue(
            ObjectNode call,
            String column,
            String captureCoverage,
            String reportAsOf,
            String priceVersion,
            Set<String> extensionColumns) {
        if ("captureCoverage".equals(column)) {
            return captureCoverage;
        }
        if ("reportAsOf".equals(column)) {
            return reportAsOf;
        }
        if ("priceVersion".equals(column) && !call.has("priceVersion")) {
            return priceVersion;
        }
        if ("rawUsageJson".equals(column)) {
            JsonNode rawUsage = call.path("rawUsage");
            if (rawUsage.isMissingNode() || rawUsage.isNull()) {
                return "";
            }
            try {
                return JSON.writeValueAsString(rawUsage);
            } catch (Exception e) {
                return "";
            }
        }
        if (column.startsWith("openai/") || column.startsWith("anthropic/")) {
            String family = call.path("providerFamily").asText("");
            if (column.startsWith("openai/") && !"openai".equalsIgnoreCase(family)) {
                return "";
            }
            if (column.startsWith("anthropic/") && !"anthropic".equalsIgnoreCase(family)) {
                return "";
            }
            String pointer = column.substring(column.indexOf('/') + 1);
            JsonNode rawUsage = call.path("rawUsage");
            JsonNode value = valueAtJsonPointer(rawUsage, pointer);
            if (value != null && value.isNumber()) {
                return value.asText();
            }
            return "";
        }
        if (extensionColumns.contains(column)) {
            JsonNode rawUsage = call.path("rawUsage");
            JsonNode value = valueAtJsonPointer(rawUsage, column);
            if (value != null && value.isNumber()) {
                return value.asText();
            }
            return "";
        }
        return call.path(column).asText("");
    }

    private static void collectNumericLeafPaths(JsonNode node, String prefix, Set<String> out) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String segment = escapeJsonPointerSegment(name);
                String path = prefix.isEmpty() ? segment : prefix + "/" + segment;
                collectNumericLeafPaths(node.get(name), path, out);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String path = prefix + "/" + i;
                collectNumericLeafPaths(node.get(i), path, out);
            }
            return;
        }
        if (node.isNumber() && !prefix.isEmpty() && !isFixedVendorPointer(prefix)) {
            out.add(prefix);
        }
    }

    private static boolean isFixedVendorPointer(String pointer) {
        for (String vendorColumn : VENDOR_COLUMNS) {
            if ("rawUsageJson".equals(vendorColumn)) {
                continue;
            }
            int slash = vendorColumn.indexOf('/');
            if (slash < 0) {
                continue;
            }
            if (vendorColumn.substring(slash + 1).equals(pointer)) {
                return true;
            }
        }
        return false;
    }

    static String escapeJsonPointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    static JsonNode valueAtJsonPointer(JsonNode root, String pointerPath) {
        if (root == null || pointerPath == null || pointerPath.isEmpty()) {
            return null;
        }
        String[] parts = pointerPath.split("/");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            String key = unescapeJsonPointerSegment(part);
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(key));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else if (current.isObject()) {
                current = current.get(key);
            } else {
                return null;
            }
        }
        return current;
    }

    private static String unescapeJsonPointerSegment(String segment) {
        return segment.replace("~1", "/").replace("~0", "~");
    }

    private static void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append('\n');
    }

    static String escape(String value) {
        if (value == null) {
            return "\"\"";
        }
        String sanitized = sanitizeFormulaPrefix(value);
        if (sanitized.contains(",") || sanitized.contains("\"") || sanitized.contains("\n") || sanitized.contains("\r")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    private static String sanitizeFormulaPrefix(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }
}
