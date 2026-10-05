package com.thingworx.things.agent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.LargeJsonCaps;
import com.thingworx.things.agent.cache.NestedPathGrammar;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.primitives.InfoTablePrimitive;

/**
 * U2 lineage-bearing nested-INFOTABLE promotion ({@code extract_nested}). Advertised in M3.
 * Path grammar: {@link NestedPathGrammar}. Playbook nested consumption remains the deferred
 * {@code playbook-nested-consumption} design gate.
 */
public final class ExtractNestedCachedResult {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractNestedCachedResult() {}

    /** BuiltInTools entrypoint. */
    public static String executeExtractNested(ToolCall call) {
        try {
            JsonNode root = MAPPER.readTree(call == null || call.getArguments() == null
                    || call.getArguments().isBlank() ? "{}" : call.getArguments());
            String sourceCacheId = textOrNull(root, "sourceCacheId");
            if (sourceCacheId == null) {
                sourceCacheId = textOrNull(root, "cacheId");
            }
            String cellPath = textOrNull(root, "cellPath");
            if (cellPath == null) {
                cellPath = textOrNull(root, "path");
            }
            return execute(sourceCacheId, cellPath);
        } catch (ArtifactCacheException e) {
            throw e;
        } catch (Exception e) {
            return error("INVALID_PARAMETERS",
                    e.getMessage() == null ? "Tool arguments must be valid JSON" : e.getMessage());
        }
    }

    /**
     * Promote the nested INFOTABLE at {@code cellPath} from {@code sourceCacheId} into a new
     * cached artifact with derived {@link SourceDescriptor} lineage.
     */
    public static String execute(String sourceCacheId, String cellPath) {
        try {
            if (sourceCacheId == null || sourceCacheId.isBlank()) {
                return error("INVALID_ARGUMENT", "sourceCacheId is required");
            }
            List<NestedPathGrammar.Segment> segments = NestedPathGrammar.parse(cellPath);
            InfoTable parent = TabularArtifactHub.lookup(sourceCacheId.trim());
            if (parent == null) {
                return error("CACHE_MISS", "sourceCacheId not found in current scope");
            }
            NestedWalkResult walked = walkToNestedInfotable(parent, segments);
            if (walked.errorCode != null) {
                return error(walked.errorCode, walked.errorMessage);
            }
            InfoTable nested = walked.table;
            LargeJsonCaps.SizeReport account = TabularArtifactHub.classifyEncodedSize(nested);
            // Nested tables cannot bypass size classification — report separately; still store.
            SourceDescriptor parentDesc = TabularArtifactHub.lookupDescriptor(sourceCacheId.trim());
            String route = "extract_nested:" + cellPath.trim();
            SourceDescriptor derived = SourceDescriptorSupport.forDerivedStore(
                    parentDesc, sourceCacheId.trim(), nested, route);
            String newCacheId = TabularArtifactHub.store(nested, derived);
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "success");
            o.put("resultKind", "INFOTABLE_CACHED");
            o.put("sourceCacheId", sourceCacheId.trim());
            o.put("cacheId", newCacheId);
            o.put("cellPath", cellPath.trim());
            o.put("rowsReturned", nested.getRowCount() == null ? 0 : nested.getRowCount().intValue());
            o.put("utf16Chars", account.utf16Chars());
            o.put("utf8Bytes", account.utf8Bytes());
            o.put("sizeClass", account.sizeClass().name());
            o.put("exceedsInvokeClassifyCap", account.exceedsInvokeClassifyCap());
            return MAPPER.writeValueAsString(o);
        } catch (NestedPathGrammar.ParseException e) {
            return error(e.code(), e.getMessage());
        } catch (ArtifactCacheException e) {
            if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
                throw e;
            }
            return error(e.code() == null ? "CACHE_FAULT" : e.code().name(), e.getMessage());
        } catch (Exception e) {
            return error("EXTRACT_NESTED_FAILED", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static NestedWalkResult walkToNestedInfotable(InfoTable root,
            List<NestedPathGrammar.Segment> segments) {
        InfoTable current = root;
        int i = 0;
        while (i < segments.size()) {
            NestedPathGrammar.Segment seg = segments.get(i);
            if (seg.kind() == NestedPathGrammar.SegmentKind.INDEX) {
                int rowCount = current.getRowCount() == null ? 0 : current.getRowCount().intValue();
                if (seg.index() < 0 || seg.index() >= rowCount) {
                    return NestedWalkResult.fail("PATH_INDEX_OUT_OF_RANGE",
                            "row index " + seg.index() + " out of range (rows=" + rowCount + ")");
                }
                // Index alone does not yield a nested table; next segment must be a field.
                if (i + 1 >= segments.size()
                        || segments.get(i + 1).kind() != NestedPathGrammar.SegmentKind.FIELD) {
                    return NestedWalkResult.fail("INVALID_PATH",
                            "index must be followed by an INFOTABLE field name");
                }
                NestedPathGrammar.Segment fieldSeg = segments.get(i + 1);
                InfoTable nested = cellInfotable(current, seg.index(), fieldSeg.fieldName());
                if (nested == null) {
                    return NestedWalkResult.fail("PATH_NOT_INFOTABLE",
                            "cell [" + seg.index() + "]." + fieldSeg.fieldName()
                                    + " is not an INFOTABLE");
                }
                current = nested;
                i += 2;
                continue;
            }
            // Field without preceding index: only legal when current table has exactly one row.
            int rowCount = current.getRowCount() == null ? 0 : current.getRowCount().intValue();
            if (rowCount != 1) {
                return NestedWalkResult.fail("INVALID_PATH",
                        "field \"" + seg.fieldName()
                                + "\" requires an explicit [rowIndex] when the table has "
                                + rowCount + " rows");
            }
            InfoTable nested = cellInfotable(current, 0, seg.fieldName());
            if (nested == null) {
                return NestedWalkResult.fail("PATH_NOT_INFOTABLE",
                        "cell [0]." + seg.fieldName() + " is not an INFOTABLE");
            }
            current = nested;
            i++;
        }
        return NestedWalkResult.ok(current);
    }

    private static InfoTable cellInfotable(InfoTable table, int rowIndex, String fieldName) {
        if (table == null || table.getDataShape() == null || table.getDataShape().getFields() == null) {
            return null;
        }
        FieldDefinition fd = null;
        for (FieldDefinition f : table.getDataShape().getFields().values()) {
            if (f != null && fieldName.equals(f.getName())) {
                fd = f;
                break;
            }
        }
        if (fd == null || fd.getBaseType() != BaseTypes.INFOTABLE) {
            return null;
        }
        Object raw = table.getRow(rowIndex).getValue(fieldName);
        if (raw instanceof InfoTable) {
            return (InfoTable) raw;
        }
        if (raw instanceof InfoTablePrimitive) {
            return ((InfoTablePrimitive) raw).getValue();
        }
        return null;
    }

    private static String textOrNull(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        String t = root.get(field).asText(null);
        return t == null || t.isBlank() ? null : t;
    }

    private static String error(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code == null ? "EXTRACT_NESTED_FAILED" : code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"EXTRACT_NESTED_FAILED\",\"message\":\"encode failed\"}";
        }
    }

    private static final class NestedWalkResult {
        final InfoTable table;
        final String errorCode;
        final String errorMessage;

        private NestedWalkResult(InfoTable table, String errorCode, String errorMessage) {
            this.table = table;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static NestedWalkResult ok(InfoTable table) {
            return new NestedWalkResult(table, null, null);
        }

        static NestedWalkResult fail(String code, String message) {
            return new NestedWalkResult(null, code, message);
        }
    }
}
