package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.joda.time.DateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.RootEntity;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.IPrimitiveType;
import com.thingworx.types.primitives.StringPrimitive;

import org.slf4j.Logger;
import com.thingworx.logging.LogUtilities;

/**
 * Built-in {@code invoke_service} and {@code fetch_cached_result} execution.
 */
public final class InvokeServiceExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(InvokeServiceExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Invoke-result LARGE classification boundary (U2 M2 / {@link com.thingworx.things.agent.cache.LargeJsonCaps}).
     * The structural answer is the U1/U2 file-backed artifact cache (docs/agent/nearterm/tool-cache-integration.md).
     */
    static final int INVOKE_SERVICE_RESULT_CHAR_CAP =
            com.thingworx.things.agent.cache.LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP;

    static final int LARGE_TABLE_ROW_THRESHOLD = ToolResultEgressGateway.llmArraySampleLimit();
    /** Max UTF-16-ish JSON length of the {@code rows} array for inline LLM replay (see large-table-replay-control). */
    private static final int FETCH_CACHED_INLINE_ROWS_JSON_CHARS = 4096;
    private static final int FETCH_CACHED_COMPACT_SAMPLE_ROWS = 3;
    static final String FETCH_CACHED_LARGE_TABLE_HINT =
            "Use fetch_cached_result only to display or browse a page. For full-table computations, use deterministic "
                    + "cached-table tools such as tabulate_cached_result / summarize_cached_result and any available "
                    + "filter modes. Do not page all rows into the LLM.";
    private static final String FETCH_CACHED_LLM_COMPACT_HINT =
            "Use tabulate_cached_result / summarize_cached_result for full-table computations; do not page all rows "
                    + "into the LLM.";
    /** @deprecated U2 M1: per-conversation Infotable map removed; ArtifactCache owns lifecycle. */
    @Deprecated
    static final int MAX_CACHED_LARGE_RESULTS_PER_CONVERSATION = 5;
    /** @deprecated U2 M1: logical TTL is owned by ArtifactCache entries, not a second map. */
    @Deprecated
    static final long CACHE_TTL_MS = 30L * 60L * 1000L;
    static final int FETCH_DEFAULT_LIMIT = 50;
    static final int FETCH_MAX_LIMIT = 200;

    private InvokeServiceExecutor() {}

    public static String executeInvokeService(ToolCall call) {
        try {
            return doInvokeService(call);
        } catch (ArtifactCacheException e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            return cacheFaultOrThrow(e);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("invoke_service unexpected failure: {}", e.getMessage(), e);
            return errorJson("INVOKE_ERROR", e.getMessage());
        }
    }

    public static String executeFetchCachedResult(ToolCall call) {
        try {
            return doFetchCached(call);
        } catch (ArtifactCacheException e) {
            return cacheFaultOrThrow(e);
        } catch (Exception e) {
            LOG.warn("fetch_cached_result failed: {}", e.getMessage());
            return errorJson("FETCH_CACHE_ERROR", e.getMessage());
        }
    }

    private static String doFetchCached(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String cacheId = text(root, "cacheId");
        if (cacheId == null || cacheId.isEmpty()) {
            LOG.warn("fetch_cached_result [{}]: {}", "MISSING_CACHE_ID", "cacheId is required");
            return errorJson("MISSING_CACHE_ID", "cacheId is required");
        }
        int offsetRequested = root.has("offset") ? root.get("offset").asInt(0) : 0;
        int limitRequested = root.has("limit") ? root.get("limit").asInt(FETCH_DEFAULT_LIMIT) : FETCH_DEFAULT_LIMIT;

        AgentToolContext.incrementFetchAfterCompleteAnswerSetIfMarked();

        String conv = AgentToolContext.getConversationId();
        InfoTable full = lookupCachedInfotable(cacheId);
        if (full == null) {
            LOG.warn("fetch_cached_result CACHE_MISS cacheId={} conversationId={}", cacheId, conv);
            return errorJson("CACHE_MISS", "No cached result for this cacheId in the current conversation (or expired).");
        }
        int total = full.getRowCount();
        // S3 / E16: shared clamp-and-echo (offset/limit effective vs requested).
        TabularPagingEcho.Page page = TabularPagingEcho.resolve(offsetRequested, limitRequested,
                FETCH_DEFAULT_LIMIT, FETCH_MAX_LIMIT, total);
        List<String> colNames = columnNames(full);
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = page.startIndex(); i < page.endIndex(); i++) {
            rows.add(rowToObject(full, i, colNames, null));
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("cacheId", cacheId);
        TabularPagingEcho.putOn(out, page);
        out.set("columns", columnsMetadataArray(full, colNames));
        out.set("rows", rows);
        LOG.info("fetch_cached_result ok cacheId={} offset={} limit={} returnedRows={} totalRows={} conv={}",
                cacheId, page.offsetEffective, page.limitEffective, page.returnedRows, total, conv);
        String fullJson = MAPPER.writeValueAsString(out);
        int rowsJsonChars = rows.toString().length();
        String turnKey = FetchCachedReplayGuard.resolveCurrentTurnKey();
        int ordinal = FetchCachedReplayGuard.incrementAndGetOrdinal(turnKey, cacheId);
        boolean forceRepeated = ordinal >= 3;
        boolean largeForReplay =
                page.returnedRows > LARGE_TABLE_ROW_THRESHOLD || rowsJsonChars > FETCH_CACHED_INLINE_ROWS_JSON_CHARS;
        if (forceRepeated || largeForReplay) {
            ObjectNode compact = MAPPER.createObjectNode();
            compact.put("status", "success");
            compact.put("cacheId", cacheId);
            TabularPagingEcho.putOn(compact, page);
            compact.set("columns", columnsMetadataArray(full, colNames));
            compact.put("sampleOnly", true);
            compact.put("rowsOmitted", true);
            ArrayNode sample = MAPPER.createArrayNode();
            int sn = Math.min(FETCH_CACHED_COMPACT_SAMPLE_ROWS, rows.size());
            for (int i = 0; i < sn; i++) {
                sample.add(rows.get(i));
            }
            compact.set("rows", sample);
            compact.put("hint", FETCH_CACHED_LLM_COMPACT_HINT);
            if (forceRepeated) {
                compact.put("repeatedPageFetch", true);
            }
            String compactJson = MAPPER.writeValueAsString(compact);
            String tcId = call.getId();
            if (tcId != null && !tcId.isEmpty()) {
                AgentToolContext.setFetchCachedStreamJsonForToolCall(tcId, fullJson);
            }
            return compactJson;
        }
        return fullJson;
    }

    private static String invokeServiceFail(String code, String message) {
        return invokeServiceFail(code, message, null, InvokeServiceParameterNormalizer.Repair.none());
    }

    private static String invokeServiceFail(String code, String message, ServiceTargetEntityTypeResolution typeRes) {
        return invokeServiceFail(code, message, typeRes, InvokeServiceParameterNormalizer.Repair.none());
    }

    private static String invokeServiceFail(String code, String message, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) {
        LOG.warn("invoke_service [{}]: {}", code, InvokeServiceErrorJson.truncateForLog(message, 800));
        return toolErrorJson(code, message, typeRes, paramRepair);
    }

    /**
     * Structured tool error JSON; optional {@code entityTypeNormalized} when {@code typeRes} is non-null and normalized.
     */
    public static String toolErrorJson(String code, String message, ServiceTargetEntityTypeResolution typeRes) {
        return toolErrorJson(code, message, typeRes, InvokeServiceParameterNormalizer.Repair.none());
    }

    /**
     * Structured tool error JSON with optional {@code entityTypeNormalized} and {@code parametersNormalized}.
     */
    public static String toolErrorJson(String code, String message, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            attachInvokeMetadata(o, typeRes, paramRepair);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"serialization failed\"}";
        }
    }

    private static void attachInvokeMetadata(ObjectNode o, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) {
        if (typeRes != null) {
            typeRes.putEntityTypeNormalizedIfPresent(o);
        }
        if (paramRepair != null) {
            paramRepair.putMetadataIfPresent(o, MAPPER);
        }
    }

    private static String mergeInvokeWireExtras(String baseJson, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) {
        try {
            ObjectNode o = (ObjectNode) MAPPER.readTree(baseJson);
            attachInvokeMetadata(o, typeRes, paramRepair);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return baseJson;
        }
    }

    private static String doInvokeService(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        String entityType = text(root, "entityType");
        String entityName = text(root, "entityName");
        String serviceName = text(root, "serviceName");
        String conv = AgentToolContext.getConversationId();
        if (entityType == null || entityType.isEmpty()) {
            return invokeServiceFail("MISSING_ENTITY_TYPE", "entityType is required");
        }
        if (serviceName == null || serviceName.isEmpty()) {
            return invokeServiceFail("MISSING_SERVICE_NAME", "serviceName is required");
        }

        LOG.info("invoke_service start: {} / {} / {} (conversationId={})",
                entityType, entityName, serviceName, conv != null ? conv : "(none)");
        long t0 = System.currentTimeMillis();

        PromptContextCacheSnapshot snap = null;
        AgentThing agentThing = AgentToolContext.getAgentThing();
        if (agentThing != null) {
            snap = agentThing.getPromptContextSnapshot();
        }
        ServiceTargetEntityTypeResolution typeRes = ServiceTargetEntityTypeResolver.resolve(entityType, snap);
        if (typeRes.isError()) {
            return toolErrorJson(typeRes.getErrorCode(), typeRes.getErrorMessage(), null);
        }
        typeRes = InvokeServiceEntityTypeNormalization.applyIfAny(typeRes, call);
        RelationshipTypes.ThingworxRelationshipTypes rel = typeRes.getEffectiveRel();

        RootEntity entity;
        if (rel == RelationshipTypes.ThingworxRelationshipTypes.Thing) {
            ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                    ScalarThingnamePreflight.gateApplicationThing("entityName", entityName);
            if (thingGate.isError()) {
                return thingGate.errorJson;
            }
            entityName = thingGate.canonicalThingName;
            if (root instanceof ObjectNode) {
                ((ObjectNode) root).put("entityName", entityName);
            }
            entity = thingGate.thing;
        } else {
            if (entityName == null || entityName.isEmpty()) {
                return invokeServiceFail("MISSING_ENTITY_NAME", "entityName is required");
            }
            try {
                entity = PlatformAccess.findAsUser(entityName, rel);
            } catch (Exception e) {
                return invokeServiceFail("ENTITY_NOT_FOUND", "Could not resolve entity: " + e.getMessage(), typeRes);
            }
            if (entity == null) {
                return invokeServiceFail("ENTITY_NOT_FOUND", typeRes.augmentEntityNotFoundMessage(entityName, rel),
                        typeRes);
            }
        }

        if (!(entity instanceof IServiceProvider)) {
            return invokeServiceFail("NOT_SERVICE_PROVIDER", "Entity does not support local service invocation.", typeRes);
        }

        IServiceProvider provider = (IServiceProvider) entity;
        ServiceDefinition sd;
        try {
            sd = provider.getInstanceServiceDefinition(serviceName);
        } catch (Exception e) {
            return invokeServiceFail("SERVICE_LOOKUP_FAILED", e.getMessage(), typeRes);
        }
        if (sd == null) {
            return invokeServiceFail("SERVICE_NOT_FOUND",
                    "Service \"" + serviceName + "\" is not available on this entity instance. "
                            + "For Things use discover_thing_members (facet=services) on the same entityName; "
                            + "for ThingTemplate / ThingShape / DataShape use describe_entity_schema.", typeRes);
        }

        if (isExplicitRemoteOnlyService(sd)) {
            LOG.warn("invoke_service blocked REMOTE_SERVICE_EDGE_ONLY: {}.{}", entityName, serviceName);
            return invokeServiceFail("REMOTE_SERVICE_EDGE_ONLY",
                    "This service is marked as Remote Service (executes on the edge device). "
                            + "The agent invokes services on the platform; use a locally executed service instead.",
                    typeRes);
        }

        InvokeServiceParameterNormalizer.Repair paramRepair = InvokeServiceParameterNormalizer.Repair.none();
        if (root.isObject()) {
            paramRepair = InvokeServiceParameterNormalizer.mergeTopLevelServiceFieldsIntoRoot((ObjectNode) root, sd,
                    MAPPER);
        }
        paramRepair = InvokeServiceParameterRepairBinding.applyIfAny(paramRepair, call);
        if (ProtectedValuePolicy.invokeServiceSuppliesPasswordParameter(sd, root.get("parameters"))) {
            ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_INPUT_BLOCKED, "invoke_service",
                    entityName + "." + serviceName);
            return toolErrorJson(ProtectedValuePolicy.CODE_INPUT_BLOCKED,
                    "Cannot supply PASSWORD parameters via the agent.", typeRes, paramRepair);
        }
        if (root.isObject()) {
            String nvqBlock = InvokeServiceNamedVtqProtection.blockedMessageForPasswordNamedVtqWrite(entity,
                    serviceName, sd, (ObjectNode) root);
            if (nvqBlock != null) {
                ParlerProtectionAudit.blocked(ProtectedValuePolicy.CODE_WRITE_BLOCKED, "invoke_service",
                        entityName + "." + serviceName);
                return toolErrorJson(ProtectedValuePolicy.CODE_WRITE_BLOCKED, nvqBlock, typeRes, paramRepair);
            }
        }
        JsonNode paramsNode = root.get("parameters");
        ValueCollection params;
        try {
            params = buildValueCollection(paramsNode, sd);
        } catch (UnsupportedRelativeLiteralException e) {
            // INFO (not WARN) — this is an LLM correction signal / telemetry source,
            // not an extension health problem. Raw value is truncated to 80 chars to bound log volume
            // (DATETIME values are temporal expressions; PII risk is low).
            // Include the stable RejectionReason classifier so telemetry queries can group without parsing the human message.
            LOG.info(
                    "invoke_service UNSUPPORTED_RELATIVE_LITERAL entity={} service={} param={} reason={} rawValue={}",
                    entityName, serviceName, e.getParamName(), e.getRejectionReason(),
                    InvokeServiceErrorJson.truncateForLog(e.getRawValue(), 80));
            // Structured wire fields rejectedParameter +
            // rejectionReason so the LLM can surgically retry without parsing the human message.
            return mergeInvokeWireExtras(InvokeServiceErrorJson.unsupportedRelativeLiteral(e), typeRes, paramRepair);
        } catch (IllegalArgumentException e) {
            return invokeServiceFail("INVALID_PARAMETERS", e.getMessage(), typeRes, paramRepair);
        }

        int paramCount = 0;
        try {
            if (params != null && params.keySet() != null) {
                paramCount = params.keySet().size();
            }
        } catch (Exception ignored) {
            paramCount = -1;
        }
        LOG.info("invoke_service invoking platform: {}.{} paramKeysApprox={}", entityName, serviceName, paramCount);

        InfoTable outer;
        try {
            outer = provider.processAPIServiceRequest(serviceName, params);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            String msg = c.getMessage() != null ? c.getMessage() : e.getMessage();
            LOG.warn("invoke_service SERVICE_EXECUTION_FAILED {}.{} after {}ms: {}",
                    entityName, serviceName, System.currentTimeMillis() - t0,
                    InvokeServiceErrorJson.truncateForLog(msg, 500));
            return toolErrorJson("SERVICE_EXECUTION_FAILED", msg, typeRes, paramRepair);
        }

        long invokeMs = System.currentTimeMillis() - t0;
        String out = formatToolResult(outer, sd, typeRes, paramRepair, entity, false);
        out = maybeRejectOversizedInvokeServiceResult(out, entityType, entityName, serviceName);
        LOG.info("invoke_service success {}.{} in {}ms (responseChars={})",
                entityName, serviceName, invokeMs, out != null ? out.length() : 0);
        return out;
    }

    /**
     * {@code invoke_service} only — not used from shared {@code formatToolResult} / direct-service formatters.
     *
     * <p>U2 E6: non-tabular oversize bodies are stored via {@link JsonArtifactHub} and returned as a
     * bounded {@code LARGE_JSON} success envelope with {@code cacheId} for {@code inspect_cached_payload}.
     * Falls back to {@code INVOKE_SERVICE_RESULT_TOO_LARGE} only when caching fails.
     */
    static String maybeRejectOversizedInvokeServiceResult(
            String body, String entityType, String entityName, String serviceName) {
        if (body == null || body.length() <= INVOKE_SERVICE_RESULT_CHAR_CAP) {
            return body;
        }
        if (isTabularExemptInvokeServiceResult(body)) {
            return body;
        }
        try {
            String cached = buildLargeJsonCachedEnvelope(body, entityType, entityName, serviceName);
            if (cached != null) {
                return cached;
            }
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("LARGE_JSON cache path failed; falling back to reject envelope: {}", e.toString());
        }
        LOG.warn("INVOKE_SERVICE_RESULT_TOO_LARGE entityType={} entityName={} service={} chars={} cap={}",
                entityType, entityName, serviceName, body.length(), INVOKE_SERVICE_RESULT_CHAR_CAP);
        return buildInvokeServiceResultTooLargeEnvelope(
                body.length(), entityType, entityName, serviceName);
    }

    /**
     * Store oversized non-tabular invoke body and return a compact LARGE_JSON success envelope.
     *
     * @return envelope JSON, or {@code null} when store fails
     */
    static String buildLargeJsonCachedEnvelope(
            String body, String entityType, String entityName, String serviceName) throws Exception {
        com.thingworx.things.agent.cache.LargeJsonCaps.SizeReport size =
                com.thingworx.things.agent.cache.LargeJsonCaps.classify(body);
        String cacheId = com.thingworx.things.agent.cache.JsonArtifactHub.store(body);
        if (cacheId == null || cacheId.isBlank()) {
            return null;
        }
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", "success");
        o.put("resultKind", "LARGE_JSON");
        o.put("cacheId", cacheId);
        o.put("utf16Chars", size.utf16Chars());
        o.put("utf8Bytes", size.utf8Bytes());
        o.put("sizeClass", size.sizeClass().name());
        o.put("limitChars", INVOKE_SERVICE_RESULT_CHAR_CAP);
        o.put("originalToolName", "invoke_service");
        o.put("entityType", entityType != null ? entityType : "");
        o.put("entityName", entityName != null ? entityName : "");
        o.put("serviceName", serviceName != null ? serviceName : "");
        o.put("hint",
                "Payload cached. Use inspect_cached_payload(mode=inspect|extract, cacheId=…) for bounded "
                        + "structure/drill-in. Nested INFOTABLE cells use extract_nested.");
        // Bounded structure hints (reuse inspect; omit if inspect fails).
        try {
            JsonNode inspect = MAPPER.readTree(
                    CachedPayloadInspect.execute(cacheId, "inspect", null));
            if ("success".equals(inspect.path("status").asText()) && inspect.has("structure")) {
                o.set("structure", inspect.get("structure"));
            } else if ("success".equals(inspect.path("status").asText())
                    && inspect.has("contentExcerpt")) {
                o.put("contentExcerpt", inspect.path("contentExcerpt").asText(""));
                o.put("parseableJson", false);
            }
        } catch (Exception ignored) {
            // hints are best-effort
        }
        ObjectNode rh = MAPPER.createObjectNode();
        ArrayNode alts = MAPPER.createArrayNode();
        alts.add("inspect_cached_payload");
        alts.add("extract_nested");
        alts.add("discover_thing_members");
        alts.add("describe_entity_schema");
        alts.add("get_property_values");
        rh.set("alternatives", alts);
        o.set("recoveryHint", rh);
        LOG.info("invoke_service LARGE_JSON cached cacheId={} utf16Chars={} sizeClass={}",
                cacheId, size.utf16Chars(), size.sizeClass());
        return MAPPER.writeValueAsString(o);
    }

    static boolean isTabularExemptInvokeServiceResult(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (!root.isObject()) {
                return false;
            }
            String resultKind = root.path("resultKind").asText("");
            if (resultKind.startsWith("INFOTABLE")) {
                return true;
            }
            JsonNode cacheId = root.get("cacheId");
            return cacheId != null && cacheId.isTextual() && !cacheId.asText("").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static String buildInvokeServiceResultTooLargeEnvelope(
            int resultChars, String entityType, String entityName, String serviceName) {
        try {
            String et = entityType != null ? entityType : "";
            String en = entityName != null ? entityName : "";
            String sn = serviceName != null ? serviceName : "";
            String thingAlias = "Thing".equalsIgnoreCase(et.trim()) ? en : "";

            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", "INVOKE_SERVICE_RESULT_TOO_LARGE");
            o.put("message",
                    "invoke_service returned " + resultChars + " chars (cap " + INVOKE_SERVICE_RESULT_CHAR_CAP
                            + ") and caching the payload failed. Prefer discover_thing_members (Thing instances), "
                            + "describe_entity_schema (schema entities), and get_property_values after listing names. "
                            + "When a LARGE_JSON cacheId is available, use inspect_cached_payload. "
                            + "For tabular data, request a smaller filtered subset or use fetch_cached_result pagination.");
            o.put("resultChars", resultChars);
            o.put("limitChars", INVOKE_SERVICE_RESULT_CHAR_CAP);
            o.put("originalToolName", "invoke_service");
            o.put("entityType", et);
            o.put("entityName", en);
            o.put("thingName", thingAlias);
            o.put("serviceName", sn);
            ObjectNode rh = MAPPER.createObjectNode();
            ArrayNode alts = MAPPER.createArrayNode();
            alts.add("inspect_cached_payload");
            alts.add("discover_thing_members");
            alts.add("describe_entity_schema");
            alts.add("get_property_values");
            rh.set("alternatives", alts);
            o.set("recoveryHint", rh);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"INVOKE_SERVICE_RESULT_TOO_LARGE\","
                    + "\"message\":\"serialization failed\"}";
        }
    }

    /**
     * Only block when metadata explicitly marks the service as remote/edge-executed.
     * RemoteThing (and similar) still run many services locally on the platform — do not reject by entity class.
     */
    /** Service aspects that mark edge-only execution; {@link CommonPropertyNames#PROP_ISREMOTE} is the platform constant for {@code isRemote}. */
    private static final String[] REMOTE_SERVICE_ASPECT_KEYS = {
            CommonPropertyNames.PROP_ISREMOTE,
            "remoteService", "isRemoteService", "remote_service", "REMOTE_SERVICE",
            "isRemoteThingService", "remoteThingService"
    };

    /** Visible for {@link ServiceTargetEntityTypeResolver} pre-HITL checks. */
    static boolean isExplicitRemoteOnlyService(ServiceDefinition sd) {
        if (sd == null) {
            return false;
        }
        try {
            Object aspects = sd.getAspects();
            if (aspects == null) {
                return false;
            }
            java.lang.reflect.Method get = aspects.getClass().getMethod("get", Object.class);
            for (String k : REMOTE_SERVICE_ASPECT_KEYS) {
                Object v = get.invoke(aspects, k);
                if (v != null && aspectValueMeansRemoteOnly(v)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // aspects API may differ across platform versions
        }
        return false;
    }

    private static boolean aspectValueMeansRemoteOnly(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static ValueCollection buildValueCollection(JsonNode paramsNode, ServiceDefinition sd) {
        ValueCollection vc = new ValueCollection();
        FieldDefinitionCollection defs = sd.getParameters();
        if (defs == null || defs.values() == null) {
            return vc;
        }

        for (FieldDefinition fd : defs.values()) {
            String name = fd.getName();
            if (name == null) {
                continue;
            }
            JsonNode node = paramsNode != null && paramsNode.isObject() ? paramsNode.get(name) : null;
            boolean missing = node == null || node.isNull();
            if (missing) {
                // Treat explicit JSON null the same as an omitted service parameter. This keeps null handling
                // uniform across JSON / VARIANT / QUERY / scalar slots; callers that need a semantic null should
                // expose an app-specific wrapper service with an explicit flag/value contract.
                continue;
            }
            if (fd.getBaseType() == BaseTypes.INFOTABLE) {
                DataShapeDefinition shape = InfotableJsonCodec.resolveDataShapeForParameter(fd);
                InfoTable it = InfotableJsonCodec.jsonToInfoTable(node, shape, name);
                try {
                    vc.SetInfoTableValue(name, it);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Parameter \"" + name + "\": " + e.getMessage());
                }
                continue;
            }
            vc.put(name, jsonToPrimitive(name, node, fd.getBaseType()));
        }
        return vc;
    }

    private static IPrimitiveType jsonToPrimitive(String paramName, JsonNode node, BaseTypes baseType) {
        // Coercion (and the §13 step 3 / §5 step 3 pre-parse defense) lives in InvokeServiceArgumentCoercion
        // so it is offline-unit-testable without triggering InvokeServiceExecutor's LogUtilities-dependent
        // static init. The QUERY-textual debug log stays here next to the invocation context.
        boolean queryTextual = baseType == BaseTypes.QUERY && InvokeServiceArgumentCoercion.isTextualQueryValue(node);
        IPrimitiveType result = InvokeServiceArgumentCoercion.coerce(paramName, node, baseType);
        if (queryTextual) {
            LOG.debug(
                    "invoke_service QUERY param={}: accepted textual JSON object (structured object is preferred)",
                    paramName);
        }
        return result;
    }

    private static String formatToolResult(InfoTable outer, ServiceDefinition sd,
            ServiceTargetEntityTypeResolution typeRes, InvokeServiceParameterNormalizer.Repair paramRepair,
            RootEntity entity, boolean surfaceConversationCacheIdForSmallInfotable)
            throws Exception {
        FieldDefinition resultFd = sd.getResultType();
        BaseTypes resultBase = resultFd != null && resultFd.getBaseType() != null
                ? resultFd.getBaseType() : BaseTypes.NOTHING;

        if (resultBase == BaseTypes.NOTHING) {
            ObjectNode ok = MAPPER.createObjectNode();
            ok.put("status", "success");
            ok.putNull("result");
            ok.put("resultKind", "void");
            return serializeToolOk(ok, typeRes, paramRepair);
        }

        if (outer == null || outer.getRowCount() == 0) {
            ObjectNode ok = MAPPER.createObjectNode();
            ok.put("status", "success");
            ok.putNull("result");
            ok.put("resultKind", "null");
            return serializeToolOk(ok, typeRes, paramRepair);
        }

        // INFOTABLE: Java services often return the table directly (ReflectionProcessor), not a wrapper row + "result".
        if (resultBase == BaseTypes.INFOTABLE) {
            InfoTable inner = ServiceResultInfotable.extractInfotableResult(outer);
            if (inner == null) {
                ObjectNode ok = MAPPER.createObjectNode();
                ok.put("status", "success");
                ok.putNull("result");
                ok.put("resultKind", "null");
                return serializeToolOk(ok, typeRes, paramRepair);
            }
            String shapeErr = validateInfotableShape(inner, resultFd);
            if (shapeErr != null) {
                return toolErrorJson("OUTPUT_SHAPE_MISMATCH", shapeErr, typeRes, paramRepair);
            }
            Thing subjectThing = entity instanceof Thing ? (Thing) entity : null;
            int rows = inner.getRowCount();
            if (rows > LARGE_TABLE_ROW_THRESHOLD) {
                return attachInvokeSuccessMetadata(
                        cacheLargeTable(inner, resultFd, subjectThing, entity, sd), typeRes, paramRepair);
            }
            ObjectNode outNode = fullTableJsonNode(inner, resultFd, subjectThing);
            putInvokeWireProvenance(outNode, entity, sd);
            if (surfaceConversationCacheIdForSmallInfotable) {
                String cacheId = storeInfotableInConversationCache(cloneInfoTableShallow(inner));
                outNode.put("cacheId", cacheId);
            }
            return attachInvokeSuccessMetadata(MAPPER.writeValueAsString(outNode), typeRes, paramRepair);
        }

        Object raw = outer.getRow(0).getValue("result");
        if (raw == null) {
            ObjectNode ok = MAPPER.createObjectNode();
            ok.put("status", "success");
            ok.putNull("result");
            ok.put("resultKind", "null");
            return serializeToolOk(ok, typeRes, paramRepair);
        }

        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("status", "success");
        ok.put("resultKind", resultBase.name());
        if (resultBase == BaseTypes.PASSWORD) {
            ok.put("result", ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder());
        } else {
            ok.set("result", primitiveOrObjectToJson(raw));
        }
        return serializeToolOk(ok, typeRes, paramRepair);
    }

    /**
     * Formats a platform service-call wrapper {@link InfoTable} for the LLM
     * using the same {@code status} / {@code resultKind} / INFOTABLE handling as built-in {@code invoke_service}
     * (no entity-type normalization metadata).
     */
    public static String formatDirectServiceResultForLlm(InfoTable outer, ServiceDefinition sd, RootEntity entity) {
        try {
            return formatToolResult(outer, sd, null, InvokeServiceParameterNormalizer.Repair.none(), entity, true);
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("formatDirectServiceResultForLlm: {}", e.getMessage(), e);
            return toolErrorJson("SERIALIZATION_FAILED",
                    e.getMessage() != null ? e.getMessage() : "serialization failed", null);
        }
    }

    private static String serializeToolOk(ObjectNode out, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) throws Exception {
        attachInvokeMetadata(out, typeRes, paramRepair);
        return MAPPER.writeValueAsString(out);
    }

    private static String attachInvokeSuccessMetadata(String json, ServiceTargetEntityTypeResolution typeRes,
            InvokeServiceParameterNormalizer.Repair paramRepair) throws Exception {
        if ((typeRes == null || !typeRes.isNormalized())
                && (paramRepair == null || !paramRepair.isRepaired())) {
            return json;
        }
        ObjectNode root = (ObjectNode) MAPPER.readTree(json);
        attachInvokeMetadata(root, typeRes, paramRepair);
        return MAPPER.writeValueAsString(root);
    }

    private static String validateInfotableShape(InfoTable inner, FieldDefinition resultFd) {
        Set<String> expected = expectedColumnNames(resultFd);
        if (expected.isEmpty()) {
            return null;
        }
        Set<String> actual = new LinkedHashSet<>(columnNames(inner));
        for (String col : expected) {
            if (!actual.contains(col)) {
                return "Missing column: " + col + "; actual=" + actual;
            }
        }
        return null;
    }

    private static Set<String> expectedColumnNames(FieldDefinition resultFd) {
        // FieldDefinition in this SDK does not expose getDataShapeDefinition(); skip strict column check.
        return Set.of();
    }

    private static List<String> columnNames(InfoTable it) {
        List<String> names = new ArrayList<>();
        try {
            DataShapeDefinition ds = it.getDataShape();
            if (ds != null) {
                FieldDefinitionCollection fc = ds.getFields();
                if (fc != null && fc.values() != null) {
                    for (FieldDefinition f : fc.values()) {
                        if (f.getName() != null) {
                            names.add(f.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("columnNames from DataShape failed (will try row keys): {}", e.getMessage());
        }
        if (names.isEmpty() && it.getRowCount() > 0) {
            ValueCollection row = it.getRow(0);
            if (row != null) {
                try {
                    for (String k : row.keySet()) {
                        names.add(k);
                    }
                } catch (Exception ignored) {
                    // ValueCollection may not expose keySet in some builds
                }
            }
        }
        return names;
    }

    private static ArrayNode columnsMetadataArray(InfoTable inner, List<String> cols) {
        ArrayNode colMeta = MAPPER.createArrayNode();
        for (String c : cols) {
            ObjectNode cn = MAPPER.createObjectNode();
            cn.put("name", c);
            cn.put("baseType", ParlerInfotableJsonUtil.wireBaseTypeForColumn(inner, c));
            colMeta.add(cn);
        }
        return colMeta;
    }

    private static ObjectNode rowToObject(InfoTable it, int rowIndex, List<String> colNames, Thing subjectThing) {
        ObjectNode o = MAPPER.createObjectNode();
        ValueCollection row = it.getRow(rowIndex);
        if (row == null) {
            return o;
        }
        String nameCol = columnKeyIgnoringCase(colNames, "name");
        String valueCol = columnKeyIgnoringCase(colNames, "value");
        boolean namedVtqShape = subjectThing != null && nameCol != null && valueCol != null;
        String propForMask = null;
        if (namedVtqShape) {
            Object nameCell = row.getValue(nameCol);
            propForMask = nameCell != null ? String.valueOf(nameCell).trim() : null;
        }
        for (String col : colNames) {
            if (namedVtqShape && valueCol.equals(col) && propForMask != null && !propForMask.isEmpty()
                    && ProtectedValuePolicy.isProtectedProperty(subjectThing, propForMask)) {
                o.put(col, ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder());
                continue;
            }
            if (ParlerInfotableJsonUtil.isPasswordColumn(it, col)) {
                o.put(col, ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder());
            } else {
                Object v = row.getValue(col);
                o.set(col, valueToJson(v));
            }
        }
        return o;
    }

    private static String columnKeyIgnoringCase(List<String> cols, String want) {
        for (String c : cols) {
            if (c != null && c.equalsIgnoreCase(want)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Large INFOTABLE results: optional cached copy with NamedVTQ {@code value} cells masked for PASSWORD properties.
     * Returns {@code null} if row copy fails — caller omits {@code cacheId}; wire samples still
     * use live {@link #rowToObject} masking.
     */
    private static InfoTable sanitizeNamedVtqRowsForLargeCache(InfoTable inner, Thing subjectThing) {
        if (inner == null || subjectThing == null) {
            return inner;
        }
        List<String> cols = columnNames(inner);
        String nameCol = columnKeyIgnoringCase(cols, "name");
        String valueCol = columnKeyIgnoringCase(cols, "value");
        if (nameCol == null || valueCol == null) {
            return inner;
        }
        try {
            InfoTable out = new InfoTable(inner.getDataShape());
            for (int i = 0; i < inner.getRowCount(); i++) {
                ValueCollection src = inner.getRow(i);
                ValueCollection dst = copyValueCollectionRowForCache(src, cols);
                Object nameCell = dst.getValue(nameCol);
                String prop = nameCell != null ? String.valueOf(nameCell).trim() : null;
                if (prop != null && !prop.isEmpty()
                        && ProtectedValuePolicy.isProtectedProperty(subjectThing, prop)) {
                    dst.put(valueCol,
                            new StringPrimitive(ParlerInfotableJsonUtil.passwordColumnLlmPlaceholder()));
                }
                out.addRow(dst);
            }
            return out;
        } catch (Exception e) {
            LOG.warn("sanitizeNamedVtqRowsForLargeCache failed: {}", e.getMessage());
            ParlerProtectionAudit.blocked("CACHE_NAMEDVTQ_UNCACHEABLE_ON_SANITIZE_FAILURE", "invoke_service",
                    "sanitizeNamedVtqRowsForLargeCache_failed");
            return null;
        }
    }

    private static ValueCollection copyValueCollectionRowForCache(ValueCollection src, List<String> cols) {
        ValueCollection dst = new ValueCollection();
        for (String c : cols) {
            Object v = src.getValue(c);
            if (v == null) {
                continue;
            }
            if (v instanceof IPrimitiveType) {
                dst.put(c, (IPrimitiveType) v);
            } else {
                dst.put(c, new StringPrimitive(String.valueOf(v)));
            }
        }
        return dst;
    }

    private static com.fasterxml.jackson.databind.JsonNode valueToJson(Object v) {
        if (v == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (v instanceof IPrimitiveType) {
            return primitiveOrObjectToJson(((IPrimitiveType) v).getValue());
        }
        if (v instanceof InfoTable) {
            try {
                return MAPPER.readTree(fullTableJson((InfoTable) v, null, null));
            } catch (Exception e) {
                return MAPPER.getNodeFactory().textNode(v.toString());
            }
        }
        return primitiveOrObjectToJson(v);
    }

    private static com.fasterxml.jackson.databind.JsonNode primitiveOrObjectToJson(Object v) {
        if (v == null) {
            return MAPPER.getNodeFactory().nullNode();
        }
        if (v instanceof String) {
            return MAPPER.getNodeFactory().textNode((String) v);
        }
        if (v instanceof Number) {
            if (v instanceof Integer || v instanceof Long) {
                return MAPPER.getNodeFactory().numberNode(((Number) v).longValue());
            }
            return MAPPER.getNodeFactory().numberNode(((Number) v).doubleValue());
        }
        if (v instanceof Boolean) {
            return MAPPER.getNodeFactory().booleanNode((Boolean) v);
        }
        if (v instanceof DateTime) {
            return MAPPER.getNodeFactory().textNode(v.toString());
        }
        return MAPPER.getNodeFactory().textNode(String.valueOf(v));
    }

    private static ObjectNode fullTableJsonNode(InfoTable inner, FieldDefinition resultFd, Thing subjectThing)
            throws Exception {
        List<String> cols = columnNames(inner);
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < inner.getRowCount(); i++) {
            arr.add(rowToObject(inner, i, cols, subjectThing));
        }
        ArrayNode colMeta = columnsMetadataArray(inner, cols);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("resultKind", "INFOTABLE");
        out.put("rowCount", inner.getRowCount());
        out.set("columns", colMeta);
        out.set("rows", arr);
        return out;
    }

    private static String fullTableJson(InfoTable inner, FieldDefinition resultFd, Thing subjectThing) throws Exception {
        return MAPPER.writeValueAsString(fullTableJsonNode(inner, resultFd, subjectThing));
    }

    /**
     * Same JSON envelope as {@code invoke_service} for INFOTABLE / INFOTABLE_LARGE (so {@code wireTable} and
     * {@link com.thingworx.things.agent.ParlerInvokeServiceInfotableTableWire} apply). Optional {@code extras} are
     * merged into the root object (e.g. {@code appliedStartTime} for alert history).
     */
    public static String formatBuiltinInfotableResult(InfoTable inner, ObjectNode extras) throws Exception {
        return formatBuiltinInfotableResult(inner, extras, com.thingworx.things.agent.source.ReadLimitFact.none());
    }

    /**
     * As above, for a caller that read {@code inner} from the platform under a row limit. The fact is handed to
     * the store call itself, because the table is cached here before {@code extras} are merged; a result small
     * enough to stay inline is not cached for the sake of the mark and carries the tool-result hint only.
     */
    public static String formatBuiltinInfotableResult(InfoTable inner, ObjectNode extras,
            com.thingworx.things.agent.source.ReadLimitFact readLimit) throws Exception {
        if (inner == null) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", "success");
            out.put("resultKind", "INFOTABLE");
            out.put("rowCount", 0);
            out.set("columns", MAPPER.createArrayNode());
            out.set("rows", MAPPER.createArrayNode());
            mergeExtras(out, extras);
            return MAPPER.writeValueAsString(out);
        }
        String body = ToolResultEgressGateway.isLargeTabularResult(inner.getRowCount())
                ? cacheLargeTable(inner, null, null, null, null, readLimit)
                : fullTableJson(inner, null, null);
        ObjectNode root = (ObjectNode) MAPPER.readTree(body);
        mergeExtras(root, extras);
        putReadLimitEvidence(root, readLimit);
        return MAPPER.writeValueAsString(root);
    }

    /**
     * Compact LLM evidence for unified {@code query_property_history} non-numeric branch (value-stream
     * {@code QueryPropertyHistory}). Always registers {@code cacheId}, caps {@code sampleRows}, never emits a
     * {@code points} array, and uses {@link #RESULT_KIND_VALUE_STREAM_HISTORY_INLINE} so numeric chart / rehydration
     * predicates stay false.
     */
    public static final String PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT = "parler.value_stream_history.compact.v1";
    public static final String RESULT_KIND_VALUE_STREAM_HISTORY_INLINE = "VALUE_STREAM_HISTORY_INLINE";

    public static String formatPropertyHistoryValueStreamCompact(InfoTable inner, Thing subjectThing, ObjectNode extras,
            int llmSampleCap) throws Exception {
        return formatPropertyHistoryValueStreamCompact(inner, subjectThing, extras, llmSampleCap, com.thingworx.things.agent.source.ReadLimitFact.none());
    }

    /** As above, with the read-limit fact of the platform read that produced {@code inner}; see {@link #putReadLimitEvidence}. */
    public static String formatPropertyHistoryValueStreamCompact(InfoTable inner, Thing subjectThing, ObjectNode extras,
            int llmSampleCap, com.thingworx.things.agent.source.ReadLimitFact readLimit) throws Exception {
        if (inner == null) {
            throw new IllegalArgumentException("inner InfoTable is null");
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("$format", PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT);
        out.put("resultKind", RESULT_KIND_VALUE_STREAM_HISTORY_INLINE);
        int totalRows = inner.getRowCount();
        String cacheId = storePrimaryWithReadLimit(cloneInfoTableShallow(inner), readLimit);
        List<String> cols = columnNames(inner);
        ArrayNode colMeta = columnsMetadataArray(inner, cols);
        int cap = Math.min(Math.max(0, llmSampleCap), Math.max(0, totalRows));
        ArrayNode sampleRows = MAPPER.createArrayNode();
        for (int i = 0; i < cap; i++) {
            sampleRows.add(rowToObject(inner, i, cols, subjectThing));
        }
        out.put("totalRows", totalRows);
        out.put("returnedRows", cap);
        out.put("sampleOnly", totalRows > cap);
        out.put("rowsOmitted", totalRows > cap);
        out.set("columns", colMeta);
        out.set("sampleRows", sampleRows);
        out.put("cacheId", cacheId);
        out.put("chartEmitted", false);
        out.put("hint",
                "Full history rows were cached under cacheId; LLM-visible sampleRows are capped. "
                        + "Use fetch_cached_result / tabulate_cached_result for deterministic follow-up.");
        mergeExtras(out, extras);
        putReadLimitEvidence(out, readLimit);
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Primary store for a table that a reader obtained under a row limit. The descriptor is the ordinary
     * {@code forPrimaryStore} one (status {@code UNKNOWN}); the reader's fact only adds the reason. Without a
     * reached fact this is exactly {@link #storeInfotableInConversationCache(InfoTable)}.
     */
    static String storePrimaryWithReadLimit(InfoTable inner, com.thingworx.things.agent.source.ReadLimitFact readLimit) throws Exception {
        if (readLimit == null || !readLimit.reached()) {
            return storeInfotableInConversationCache(inner);
        }
        return storeInfotableInConversationCache(inner,
                com.thingworx.things.agent.source.SourceDescriptorSupport.withReadLimitFact(
                        com.thingworx.things.agent.source.SourceDescriptorSupport.forPrimaryStore(inner,
                                "invoke_service"),
                        readLimit));
    }

    /**
     * Tool-result evidence of a reached read limit: a boolean and one sentence for the model. Written only when the
     * limit was reached, so every other result is byte for byte what it was. The same fact must already have been
     * given to the store call of this result, when it has one, so the hint and the cached descriptor agree.
     */
    public static void putReadLimitEvidence(ObjectNode root, com.thingworx.things.agent.source.ReadLimitFact readLimit) {
        if (root == null || readLimit == null || !readLimit.reached()) {
            return;
        }
        root.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED, true);
        root.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_NOTE, readLimit.note());
    }

    private static void mergeExtras(ObjectNode root, ObjectNode extras) {
        if (extras == null) {
            return;
        }
        for (Iterator<String> it = extras.fieldNames(); it.hasNext();) {
            String f = it.next();
            root.set(f, extras.get(f));
        }
    }

    /**
     * Stores an {@link InfoTable} via the U2 {@link TabularArtifactHub} (opaque {@code ArtifactCache}).
     * Other built-in tools (e.g. {@code query_entities}) use this for large row sets.
     *
     * @return public cacheId ({@code ArtifactRef} UUID text) for {@link #executeFetchCachedResult}
     */
    public static String storeInfotableInConversationCache(InfoTable inner) throws Exception {
        return storeInfotableInConversationCache(inner,
                com.thingworx.things.agent.source.SourceDescriptorSupport.forPrimaryStore(inner,
                        "invoke_service"));
    }

    /**
     * Store with an explicit runtime {@link com.thingworx.things.agent.source.SourceDescriptor}
     * (derive/compose paths).
     */
    public static String storeInfotableInConversationCache(InfoTable inner,
            com.thingworx.things.agent.source.SourceDescriptor descriptor) throws Exception {
        return TabularArtifactHub.store(cloneInfoTableShallow(inner), descriptor);
    }

    /** Runtime source descriptor for a cache id in the current conversation, or {@code null}. */
    public static com.thingworx.things.agent.source.SourceDescriptor lookupSourceDescriptor(String cacheId) {
        return TabularArtifactHub.lookupDescriptor(cacheId);
    }

    /**
     * Historical live-cache resurrection API — <strong>retired (BP9 / U2 M4)</strong>.
     * Always returns {@code false}; compact Stream rehydrate must not recreate live
     * {@code ArtifactCache} entries from transcript evidence.
     */
    public static boolean restoreInfotableInConversationCache(String conversationId, String cacheId, InfoTable inner) {
        return false;
    }

    /**
     * Diagnostic snapshot of conversation cache ids. U2 M1 no longer maintains a FIFO Infotable
     * deque; returns empty (AgentLoop first-hit diagnostics tolerate empty).
     */
    public static List<String> snapshotConversationInfotableCacheIds() {
        return Collections.emptyList();
    }

    public static int largeTableRowThreshold() {
        return LARGE_TABLE_ROW_THRESHOLD;
    }

    private static void putInvokeWireProvenance(ObjectNode out, RootEntity entity, ServiceDefinition sd) {
        if (out == null) {
            return;
        }
        if (entity != null) {
            String name = entity.getName();
            if (name != null && !name.isBlank()) {
                out.put("entityName", name);
            }
        }
        if (sd != null) {
            String serviceName = sd.getName();
            if (serviceName != null && !serviceName.isBlank()) {
                out.put("serviceName", serviceName);
            }
        }
    }

    private static String cacheLargeTable(InfoTable inner, FieldDefinition resultFd, Thing subjectThing,
            RootEntity entity, ServiceDefinition sd) throws Exception {
        return cacheLargeTable(inner, resultFd, subjectThing, entity, sd, com.thingworx.things.agent.source.ReadLimitFact.none());
    }

    private static String cacheLargeTable(InfoTable inner, FieldDefinition resultFd, Thing subjectThing,
            RootEntity entity, ServiceDefinition sd, com.thingworx.things.agent.source.ReadLimitFact readLimit)
            throws Exception {
        InfoTable sanitized = sanitizeNamedVtqRowsForLargeCache(inner, subjectThing);
        String cacheId = null;
        InfoTable tableForSamples = inner;
        if (sanitized != null) {
            cacheId = storePrimaryWithReadLimit(sanitized, readLimit);
            tableForSamples = sanitized;
        }

        List<String> cols = columnNames(tableForSamples);
        int sampleN = Math.min(LARGE_TABLE_ROW_THRESHOLD, tableForSamples.getRowCount());
        ArrayNode sample = MAPPER.createArrayNode();
        for (int i = 0; i < sampleN; i++) {
            sample.add(rowToObject(tableForSamples, i, cols, subjectThing));
        }
        ArrayNode colMeta = columnsMetadataArray(tableForSamples, cols);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("resultKind", "INFOTABLE_LARGE");
        out.put("totalRows", tableForSamples.getRowCount());
        out.set("columns", colMeta);
        out.set("sampleRows", sample);
        if (cacheId != null) {
            out.put("cacheId", cacheId);
            out.put("hint", FETCH_CACHED_LARGE_TABLE_HINT);
        } else {
            out.put("hint",
                    "Large result; paging via fetch_cached_result is unavailable (cache copy could not be prepared).");
        }
        putInvokeWireProvenance(out, entity, sd);
        return MAPPER.writeValueAsString(out);
    }

    /** Shallow copy: same row data references; sufficient for read-only paging. */
    private static InfoTable cloneInfoTableShallow(InfoTable src) {
        try {
            InfoTable copy = new InfoTable(src.getDataShape());
            for (int i = 0; i < src.getRowCount(); i++) {
                copy.addRow(src.getRow(i));
            }
            return copy;
        } catch (Exception e) {
            return src;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    private static String errorJson(String code, String message) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"serialization failed\"}";
        }
    }

    private static String cacheFaultOrThrow(ArtifactCacheException e) {
        if (e.code() == ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE) {
            throw e;
        }
        return errorJson(e.code().name(), e.getMessage());
    }

    /**
     * Resolves a large-result cache entry via {@link TabularArtifactHub} / opaque ArtifactCache.
     *
     * @return the cached table, or {@code null} on uniform miss
     */
    public static InfoTable lookupCachedInfotable(String cacheId) {
        return TabularArtifactHub.lookup(cacheId);
    }

    /**
     * Like {@link #lookupCachedInfotable(String)} with an explicit conversation id for Stream
     * rehydration. Namespace is the Core {@link com.thingworx.things.agent.execution.RunInvocationContext}
     * scope, not the conversation string; {@code conversationId} blank still misses for API parity.
     */
    public static InfoTable lookupCachedInfotableForConversation(String conversationId, String cacheId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return TabularArtifactHub.lookupForConversation(conversationId, cacheId);
    }
}
