package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.EntityReference;
import com.thingworx.implementation.ServiceImplementation;
import com.thingworx.logging.LogUtilities;
import com.thingworx.metadata.EventDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.PropertyDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.metadata.collections.EventDefinitionCollection;
import com.thingworx.metadata.collections.FieldDefinitionCollection;
import com.thingworx.metadata.collections.ServiceDefinitionCollection;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.subscriptions.MultiEventSubscription;
import com.thingworx.subscriptions.collections.MultiEventSubscriptionCollection;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.events.EventDescriptor;

/**
 * Built-in {@code discover_thing_members}: visibility-aware concrete {@link Thing} member discovery
 * (properties, services, events, and configured subscriptions in v1) with facet-bounded responses aligned to
 * {@link DescribeEntitySchemaExecutor} envelopes.
 *
 * @see docs/agent/thing-member-discovery.md
 */
public final class DiscoverThingMembersExecutor {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(DiscoverThingMembersExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_MAX_ITEMS = 80;
    private static final int DESC_TRUNCATE = 400;

    /** v1 model schema + executor facets (configured {@code subscriptions} list; no singular subscription facet). */
    private static final List<String> V1_FACETS = List.of(
            "properties", "property", "services", "service", "events", "event", "subscriptions");

    private static final java.util.Set<String> ALLOWED_ROOT_KEYS = java.util.Set.of(
            "thingName", "facet", "memberName", "namePrefix", "category", "baseType", "dataShape", "offset", "maxItems");

    private DiscoverThingMembersExecutor() {}

    public static String execute(ToolCall call) {
        try {
            return doExecute(call);
        } catch (Exception e) {
            LOG.warn("discover_thing_members: {}", e.getMessage());
            return errorJson("DISCOVER_THING_MEMBERS_ERROR", e.getMessage(), null);
        }
    }

    /**
     * Shared Thing member discovery for {@code discover_thing_members} and Thing-targeted legacy tools.
     * {@code argumentsJson} is a JSON object with at least {@code thingName} and {@code facet}.
     */
    static String executeJsonArguments(String argumentsJson) throws Exception {
        JsonNode root = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        return run(root);
    }

    private static String doExecute(ToolCall call) throws Exception {
        JsonNode root = MAPPER.readTree(call.getArguments() == null ? "{}" : call.getArguments());
        return run(root);
    }

    private static String run(JsonNode root) throws Exception {
        PreThingParse pre = parseBeforeThingResolution(root);
        if (pre.errorJson != null) {
            return pre.errorJson;
        }
        ScalarThingnamePreflight.ApplicationThingGateOutcome thingGate =
                ScalarThingnamePreflight.gateApplicationThing("thingName", pre.thingNameTrimmed);
        if (thingGate.isError()) {
            return thingGate.errorJson;
        }
        Thing thing = thingGate.thing;
        String resolvedName = thingGate.canonicalThingName;
        return executeFacets(thing, resolvedName, root, pre.facetNorm);
    }

    /**
     * Per-name visibility gate (production: {@link Thing#getInstancePropertyDefinitionIfVisible(String)}).
     * Exposed for offline tests that cannot construct a platform {@link Thing} instance.
     */
    @FunctionalInterface
    interface VisiblePropertyLookup {
        PropertyDefinition ifVisible(String propertyName) throws Exception;
    }

    private static final class PreThingParse {
        final String errorJson;
        final String thingNameTrimmed;
        final String facetNorm;

        private PreThingParse(String errorJson, String thingNameTrimmed, String facetNorm) {
            this.errorJson = errorJson;
            this.thingNameTrimmed = thingNameTrimmed;
            this.facetNorm = facetNorm;
        }

        static PreThingParse error(String json) {
            return new PreThingParse(json, null, null);
        }

        static PreThingParse ok(String thingNameTrimmed, String facetNorm) {
            return new PreThingParse(null, thingNameTrimmed, facetNorm);
        }
    }

    private static PreThingParse parseBeforeThingResolution(JsonNode root) throws Exception {
        if (!root.isObject()) {
            return PreThingParse.error(errorJson("INVALID_ARGUMENTS", "Arguments must be a JSON object.", null));
        }
        String keyErr = validateRootKeys(root);
        if (keyErr != null) {
            return PreThingParse.error(keyErr);
        }
        String thingName = text(root, "thingName");
        if (thingName == null || thingName.isBlank()) {
            return PreThingParse.error(ScalarThingnamePreflight.thingNameValueRequiredJson("thingName"));
        }
        String facet = text(root, "facet");
        if (facet == null || facet.isEmpty()) {
            facet = "properties";
        } else {
            facet = facet.trim();
        }
        String facetNorm = facet.toLowerCase(Locale.ROOT);
        if (!V1_FACETS.contains(facetNorm)) {
            return PreThingParse.error(errorJson("INVALID_FACET", "Unknown facet \"" + facet + "\".", null));
        }
        if ("property".equals(facetNorm) || "service".equals(facetNorm) || "event".equals(facetNorm)) {
            String memberName = text(root, "memberName");
            if (memberName == null || memberName.isBlank()) {
                return PreThingParse.error(errorJson("MISSING_MEMBER_NAME", "memberName is required for facet \"" + facet + "\".",
                        null));
            }
        } else if (isListFacet(facetNorm)) {
            String memberName = text(root, "memberName");
            if (memberName != null && !memberName.isBlank()) {
                return PreThingParse.error(errorJson("UNSUPPORTED_TOOL_PARAMETER",
                        "memberName is not supported for list facet \"" + facet + "\". Use facet \"property\", "
                                + "\"service\", or \"event\" with memberName for one member, or set namePrefix to narrow "
                                + "the list.",
                        recoveryHint("discover_thing_members", "namePrefix")));
            }
        }
        return PreThingParse.ok(thingName.trim(), facetNorm);
    }

    private static String executeFacets(Thing thing, String resolvedName, JsonNode root, String facetNorm)
            throws Exception {
        switch (facetNorm) {
            case "properties":
                return writePropertiesList(thing, resolvedName, root);
            case "property":
                return writePropertySingular(thing, resolvedName, root, text(root, "memberName").trim());
            case "services":
                return writeServicesList(thing, resolvedName, root);
            case "service":
                return writeServiceSingular(thing, resolvedName, root, text(root, "memberName").trim());
            case "events":
                return writeEventsList(thing, resolvedName, root);
            case "event":
                return writeEventSingular(thing, resolvedName, root, text(root, "memberName").trim());
            case "subscriptions":
                return writeSubscriptionsList(thing, resolvedName, root);
            default:
                return errorJson("INVALID_FACET", "facet \"" + facetNorm + "\".", null);
        }
    }

    private static boolean isListFacet(String facetNorm) {
        return "properties".equals(facetNorm) || "services".equals(facetNorm) || "events".equals(facetNorm)
                || "subscriptions".equals(facetNorm);
    }

    private static String validateRootKeys(JsonNode root) throws Exception {
        if (root.has("includePrivateServices")) {
            return errorJson("UNSUPPORTED_TOOL_PARAMETER",
                    "includePrivateServices is not supported in discover_thing_members v1.", null);
        }
        if (root.has("subscriptionScope")) {
            return errorJson("UNSUPPORTED_TOOL_PARAMETER",
                    "subscriptionScope is not supported in discover_thing_members v1.", null);
        }
        java.util.Iterator<String> iter = root.fieldNames();
        while (iter.hasNext()) {
            String k = iter.next();
            if (!ALLOWED_ROOT_KEYS.contains(k)) {
                return errorJson("UNSUPPORTED_TOOL_PARAMETER",
                        "Unsupported parameter \"" + k + "\" for discover_thing_members v1.", null);
            }
        }
        return null;
    }

    private static String writePropertiesList(Thing thing, String resolvedName, JsonNode root) throws Exception {
        List<PropertyDefinition> raw = listVisiblePropertyDefinitions(thing);
        List<PropertyDefinition> matched = DescribeEntitySchemaExecutor.filterProperties(raw, root);
        int total = matched.size();
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, total);
        ArrayNode items = slicePropertyPage(matched, page);
        ObjectNode out = baseSuccess(resolvedName, "properties", "effective");
        out.set("items", items);
        out.put("totalMatched", total);
        out.put("offset", page.offset);
        out.put("returned", page.returned);
        out.put("hasMore", page.endExclusive < total);
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Paged list bounds shared with {@link DescribeEntitySchemaExecutor#computeListPageBounds(long, long, int)} —
     * clamps offset, guards overflow, and keeps {@code returned} non-negative.
     */
    static DescribeEntitySchemaExecutor.ListPageBounds listPageBoundsForRequest(JsonNode root, int totalMatched) {
        long rawOffset = root.has("offset") && !root.get("offset").isNull() ? root.get("offset").asLong(0) : 0L;
        long rawMaxItems = root.has("maxItems") && !root.get("maxItems").isNull()
                ? root.get("maxItems").asLong(DEFAULT_MAX_ITEMS)
                : (long) DEFAULT_MAX_ITEMS;
        return DescribeEntitySchemaExecutor.computeListPageBounds(rawOffset, rawMaxItems, totalMatched);
    }

    static ArrayNode slicePropertyPageForTest(List<PropertyDefinition> matched, JsonNode root) throws Exception {
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, matched.size());
        return slicePropertyPage(matched, page);
    }

    static ArrayNode sliceServicePageForTest(List<ServiceDefinition> matched, JsonNode root) throws Exception {
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, matched.size());
        return sliceServicePage(matched, page);
    }

    private static ArrayNode slicePropertyPage(List<PropertyDefinition> matched,
            DescribeEntitySchemaExecutor.ListPageBounds page) throws Exception {
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = page.offset; i < page.endExclusive; i++) {
            items.add(propertyRowFull(matched.get(i)));
        }
        return items;
    }

    private static ArrayNode sliceServicePage(List<ServiceDefinition> matched,
            DescribeEntitySchemaExecutor.ListPageBounds page) {
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = page.offset; i < page.endExclusive; i++) {
            items.add(DescribeEntitySchemaExecutor.compactServiceRow(matched.get(i)));
        }
        return items;
    }

    private static ArrayNode sliceEventPage(List<EventDefinition> matched,
            DescribeEntitySchemaExecutor.ListPageBounds page) {
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = page.offset; i < page.endExclusive; i++) {
            items.add(eventListRow(matched.get(i)));
        }
        return items;
    }

    static ArrayNode sliceEventPageForTest(List<EventDefinition> matched, JsonNode root) throws Exception {
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, matched.size());
        return sliceEventPage(matched, page);
    }

    private static ObjectNode eventListRow(EventDefinition ed) {
        ObjectNode row = DescribeEntitySchemaExecutor.compactEventRow(ed);
        String dsn = ed.getDataShapeName();
        if (dsn != null && !dsn.isEmpty()) {
            row.put("dataShape", dsn);
        }
        return row;
    }

    private static List<EventDefinition> listInstanceEventDefinitions(Thing thing) {
        EventDefinitionCollection coll = thing.getInstanceEventDefinitions();
        if (coll == null || coll.values() == null) {
            return List.of();
        }
        return new ArrayList<>(coll.values());
    }

    /** Optional {@code dataShape} filter on {@code events} list (matches {@link EventDefinition#getDataShapeName()}). */
    static List<EventDefinition> filterEventDefinitionsByDataShape(List<EventDefinition> matched, JsonNode root) {
        String want = text(root, "dataShape");
        if (want == null || want.isBlank()) {
            return matched;
        }
        String w = want.trim();
        List<EventDefinition> out = new ArrayList<>();
        for (EventDefinition ed : matched) {
            if (ed == null) {
                continue;
            }
            String ds = ed.getDataShapeName();
            if (ds != null && ds.equalsIgnoreCase(w)) {
                out.add(ed);
            }
        }
        out.sort(Comparator.comparing(EventDefinition::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return out;
    }

    private static String writeEventsList(Thing thing, String resolvedName, JsonNode root) throws Exception {
        List<EventDefinition> all = listInstanceEventDefinitions(thing);
        List<EventDefinition> matched = DescribeEntitySchemaExecutor.filterEvents(all, root);
        matched = filterEventDefinitionsByDataShape(matched, root);
        int total = matched.size();
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, total);
        ArrayNode items = sliceEventPage(matched, page);
        ObjectNode out = baseSuccess(resolvedName, "events", "effective");
        out.set("items", items);
        out.put("totalMatched", total);
        out.put("offset", page.offset);
        out.put("returned", page.returned);
        out.put("hasMore", page.endExclusive < total);
        return MAPPER.writeValueAsString(out);
    }

    private static EventDefinition resolveEventDefinition(Thing thing, String memberName) throws Exception {
        try {
            EventDefinition ed = thing.getInstanceEventDefinition(memberName);
            if (ed != null) {
                return ed;
            }
        } catch (Exception ignored) {
            // fall through to case-insensitive scan
        }
        for (EventDefinition cand : listInstanceEventDefinitions(thing)) {
            if (cand != null && cand.getName() != null && cand.getName().equalsIgnoreCase(memberName)) {
                return cand;
            }
        }
        return null;
    }

    private static String writeEventSingular(Thing thing, String resolvedName, JsonNode root, String memberName)
            throws Exception {
        EventDefinition ed = resolveEventDefinition(thing, memberName);
        if (ed == null) {
            return errorJson("EVENT_NOT_FOUND_OR_NOT_VISIBLE",
                    "Event \"" + memberName + "\" not found on this Thing. "
                            + "List candidates with facet \"events\" (optionally set namePrefix).",
                    recoveryHint("discover_thing_members", "namePrefix"));
        }
        ObjectNode out = baseSuccess(resolvedName, "event", "effective");
        out.set("event", eventDetailNode(ed));
        out.put("totalMatched", 1);
        out.put("offset", 0);
        out.put("returned", 1);
        out.put("hasMore", false);
        return MAPPER.writeValueAsString(out);
    }

    private static ObjectNode eventDetailNode(EventDefinition ed) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("name", ed.getName());
        String desc = ed.getDescription();
        if (desc != null && desc.length() > DESC_TRUNCATE) {
            desc = desc.substring(0, DESC_TRUNCATE) + "...";
        }
        o.put("description", desc != null ? desc : "");
        String cat = ed.getCategory();
        if (cat != null && !cat.isEmpty()) {
            o.put("category", cat);
        }
        String dsn = ed.getDataShapeName();
        if (dsn != null && !dsn.isEmpty()) {
            o.put("dataShape", dsn);
        }
        try {
            java.lang.reflect.Method m = ed.getClass().getMethod("isInvocable");
            Object v = m.invoke(ed);
            if (v instanceof Boolean) {
                o.put("invocable", (Boolean) v);
            }
        } catch (Exception ignored) {
            // optional platform fields
        }
        try {
            java.lang.reflect.Method m = ed.getClass().getMethod("isLocalOnly");
            Object v = m.invoke(ed);
            if (v instanceof Boolean) {
                o.put("localOnly", (Boolean) v);
            }
        } catch (Exception ignored) {
            // optional platform fields
        }
        return o;
    }

    private static List<MultiEventSubscription> listConfiguredSubscriptions(Thing thing) {
        MultiEventSubscriptionCollection coll = thing.getInstanceMultiEventSubscriptions();
        if (coll == null || coll.values() == null) {
            return List.of();
        }
        return new ArrayList<>(coll.values());
    }

    /** {@code namePrefix} filter for {@code subscriptions} list (case-insensitive prefix on subscription name). */
    static List<MultiEventSubscription> filterSubscriptionsByNamePrefix(List<MultiEventSubscription> all, JsonNode root) {
        String prefix = text(root, "namePrefix");
        List<MultiEventSubscription> work;
        if (prefix == null || prefix.isEmpty()) {
            work = new ArrayList<>(all);
        } else {
            String p = prefix.toLowerCase(Locale.ROOT);
            work = new ArrayList<>();
            for (MultiEventSubscription s : all) {
                if (s == null || s.getName() == null) {
                    continue;
                }
                if (s.getName().toLowerCase(Locale.ROOT).startsWith(p)) {
                    work.add(s);
                }
            }
        }
        work.sort(Comparator.comparing(MultiEventSubscription::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return work;
    }

    private static ArrayNode sliceSubscriptionPage(List<MultiEventSubscription> matched,
            DescribeEntitySchemaExecutor.ListPageBounds page) {
        ArrayNode items = MAPPER.createArrayNode();
        for (int i = page.offset; i < page.endExclusive; i++) {
            items.add(subscriptionListRow(matched.get(i)));
        }
        return items;
    }

    static ArrayNode sliceSubscriptionPageForTest(List<MultiEventSubscription> matched, JsonNode root) throws Exception {
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, matched.size());
        return sliceSubscriptionPage(matched, page);
    }

    private static ObjectNode subscriptionListRow(MultiEventSubscription sub) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("name", sub.getName() != null ? sub.getName() : "");
        row.put("enabled", sub.isEnabled());
        row.put("multiEvent", sub.isMultiEvent());
        row.put("scope", "configured");
        EntityReference owner = sub.getOwner();
        if (owner != null) {
            if (owner.getName() != null && !owner.getName().isEmpty()) {
                row.put("owner", owner.getName());
            }
            if (owner.getType() != null) {
                row.put("ownerType", owner.getType().name());
            }
        }
        ServiceImplementation sim = sub.getServiceImplementation();
        if (sim != null && sim.getHandlerName() != null && !sim.getHandlerName().isEmpty()) {
            row.put("handlerName", sim.getHandlerName());
        }
        ArrayNode ev = MAPPER.createArrayNode();
        try {
            List<EventDescriptor> events = sub.getEvents();
            if (events != null) {
                for (EventDescriptor ed : events) {
                    if (ed == null) {
                        continue;
                    }
                    String d = ed.getLocalEventDescriptor();
                    if (d == null || d.isEmpty()) {
                        d = ed.getEventDescriptor();
                    }
                    if (d != null && !d.isEmpty()) {
                        ev.add(d);
                    }
                }
            }
        } catch (Exception ignored) {
            // optional platform reads
        }
        row.set("eventDescriptors", ev);
        return row;
    }

    private static String writeSubscriptionsList(Thing thing, String resolvedName, JsonNode root) throws Exception {
        List<MultiEventSubscription> all = listConfiguredSubscriptions(thing);
        List<MultiEventSubscription> matched = filterSubscriptionsByNamePrefix(all, root);
        int total = matched.size();
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, total);
        ArrayNode items = sliceSubscriptionPage(matched, page);
        ObjectNode out = baseSuccess(resolvedName, "subscriptions", "effective");
        out.set("items", items);
        out.put("totalMatched", total);
        out.put("offset", page.offset);
        out.put("returned", page.returned);
        out.put("hasMore", page.endExclusive < total);
        return MAPPER.writeValueAsString(out);
    }

    /**
     * Lists property definitions that pass per-name visibility ({@link Thing#getInstancePropertyDefinitionIfVisible}).
     */
    static List<PropertyDefinition> listVisiblePropertyDefinitions(Thing thing) throws Exception {
        Object coll = thing.getClass().getMethod("getInstancePropertyDefinitions").invoke(thing);
        if (coll == null) {
            return List.of();
        }
        java.lang.reflect.Method values = coll.getClass().getMethod("values");
        Object v = values.invoke(coll);
        if (!(v instanceof Iterable)) {
            return List.of();
        }
        List<PropertyDefinition> raw = new ArrayList<>();
        for (Object o : (Iterable<?>) v) {
            if (!(o instanceof PropertyDefinition)) {
                continue;
            }
            PropertyDefinition pd = (PropertyDefinition) o;
            if (pd.getName() == null || pd.getName().isEmpty()) {
                continue;
            }
            raw.add(pd);
        }
        return listVisiblePropertyDefinitionsUsing(raw, thing::getInstancePropertyDefinitionIfVisible);
    }

    /**
     * Core of {@link #listVisiblePropertyDefinitions(Thing)} — each raw definition name must receive a non-null
     * visibility result to appear in the list.
     */
    static List<PropertyDefinition> listVisiblePropertyDefinitionsUsing(Iterable<PropertyDefinition> rawDefs,
            VisiblePropertyLookup lookup) throws Exception {
        List<PropertyDefinition> all = new ArrayList<>();
        for (PropertyDefinition pd : rawDefs) {
            if (pd == null || pd.getName() == null || pd.getName().isEmpty()) {
                continue;
            }
            PropertyDefinition vis = lookup.ifVisible(pd.getName());
            if (vis != null) {
                all.add(vis);
            }
        }
        return all;
    }

    /**
     * Resolves a visible property for singular {@code property}: exact {@code getInstancePropertyDefinitionIfVisible}
     * first, then case-insensitive match among names that already pass the list facet visibility filter.
     */
    private static PropertyDefinition resolveVisiblePropertyDefinition(Thing thing, String memberName) throws Exception {
        PropertyDefinition pd = thing.getInstancePropertyDefinitionIfVisible(memberName);
        if (pd != null) {
            return pd;
        }
        for (PropertyDefinition cand : listVisiblePropertyDefinitions(thing)) {
            if (cand != null && cand.getName() != null && cand.getName().equalsIgnoreCase(memberName)) {
                return thing.getInstancePropertyDefinitionIfVisible(cand.getName());
            }
        }
        return null;
    }

    private static String writePropertySingular(Thing thing, String resolvedName, JsonNode root, String memberName)
            throws Exception {
        PropertyDefinition pd = resolveVisiblePropertyDefinition(thing, memberName);
        if (pd == null) {
            return errorJson("PROPERTY_NOT_FOUND_OR_NOT_VISIBLE",
                    "Property \"" + memberName + "\" not found or not visible on this Thing. "
                            + "List candidates with facet \"properties\" (optionally set namePrefix).",
                    recoveryHint("discover_thing_members", "namePrefix"));
        }
        ObjectNode out = baseSuccess(resolvedName, "property", "effective");
        out.set("property", propertyRowFull(pd));
        out.put("totalMatched", 1);
        out.put("offset", 0);
        out.put("returned", 1);
        out.put("hasMore", false);
        return MAPPER.writeValueAsString(out);
    }

    private static String writeServicesList(Thing thing, String resolvedName, JsonNode root) throws Exception {
        List<ServiceDefinition> pub = listPublicServiceDefinitions(thing);
        List<ServiceDefinition> matched = filterServicesWithResultBaseType(pub, root);
        int total = matched.size();
        DescribeEntitySchemaExecutor.ListPageBounds page = listPageBoundsForRequest(root, total);
        ArrayNode items = sliceServicePage(matched, page);
        ObjectNode out = baseSuccess(resolvedName, "services", "effective");
        out.set("items", items);
        out.put("totalMatched", total);
        out.put("offset", page.offset);
        out.put("returned", page.returned);
        out.put("hasMore", page.endExclusive < total);
        return MAPPER.writeValueAsString(out);
    }

    private static List<ServiceDefinition> listPublicServiceDefinitions(Thing thing) {
        ServiceDefinitionCollection coll = thing.getInstancePublicServiceDefinitions();
        if (coll == null || coll.values() == null) {
            return List.of();
        }
        return new ArrayList<>(coll.values());
    }

    private static List<ServiceDefinition> filterServicesWithResultBaseType(List<ServiceDefinition> all, JsonNode root) {
        List<ServiceDefinition> base = DescribeEntitySchemaExecutor.filterServices(all, root);
        String baseType = text(root, "baseType");
        if (baseType == null || baseType.isBlank()) {
            return base;
        }
        String want = baseType.trim();
        List<ServiceDefinition> out = new ArrayList<>();
        for (ServiceDefinition sd : base) {
            FieldDefinition rt = sd.getResultType();
            String bt = rt != null && rt.getBaseType() != null ? rt.getBaseType().name() : "";
            if (bt.equalsIgnoreCase(want)) {
                out.add(sd);
            }
        }
        out.sort(Comparator.comparing(ServiceDefinition::getName, Comparator.nullsFirst(String::compareToIgnoreCase)));
        return out;
    }

    private static String writeServiceSingular(Thing thing, String resolvedName, JsonNode root, String serviceName)
            throws Exception {
        ServiceDefinition sd = findPublicServiceByName(thing, serviceName);
        if (sd == null) {
            return errorJson("SERVICE_NOT_FOUND_OR_NOT_VISIBLE",
                    "Public service \"" + serviceName + "\" not found on this Thing. "
                            + "List candidates with facet \"services\" (optionally set namePrefix).",
                    recoveryHint("discover_thing_members", "namePrefix"));
        }
        ObjectNode out = baseSuccess(resolvedName, "service", "effective");
        ObjectNode svc = MAPPER.createObjectNode();
        svc.put("name", sd.getName());
        String desc = sd.getDescription();
        if (desc != null && desc.length() > DESC_TRUNCATE) {
            desc = desc.substring(0, DESC_TRUNCATE) + "...";
        }
        svc.put("description", desc != null ? desc : "");

        ArrayNode params = MAPPER.createArrayNode();
        FieldDefinitionCollection pdefs = sd.getParameters();
        if (pdefs != null && pdefs.values() != null) {
            for (FieldDefinition fd : pdefs.values()) {
                if (fd.getName() == null) {
                    continue;
                }
                ObjectNode p = MAPPER.createObjectNode();
                p.put("name", fd.getName());
                p.put("baseType", fd.getBaseType() != null ? fd.getBaseType().name() : "STRING");
                p.put("required", isRequiredParam(fd));
                if (fd.getBaseType() == BaseTypes.INFOTABLE) {
                    String ds = aspectString(fd, "dataShape");
                    if (ds != null && !ds.isEmpty()) {
                        p.put("dataShape", ds);
                    }
                }
                params.add(p);
            }
        }
        svc.set("parameters", params);

        ObjectNode res = MAPPER.createObjectNode();
        FieldDefinition rt = sd.getResultType();
        if (rt != null && rt.getBaseType() != null) {
            res.put("baseType", rt.getBaseType().name());
            if (rt.getBaseType() == BaseTypes.INFOTABLE) {
                String ds = aspectString(rt, "dataShape");
                if (ds != null && !ds.isEmpty()) {
                    res.put("dataShape", ds);
                }
            }
        } else {
            res.put("baseType", "NOTHING");
        }
        svc.set("result", res);
        out.set("service", svc);
        PromptContextCacheSnapshot snap = promptSnapshotOrNull();
        out.set("invokeExample", MetadataDiscoveryExecutor.buildInvokeExampleForResolvedService(snap, "Thing",
                resolvedName, sd));
        return MAPPER.writeValueAsString(out);
    }

    private static ServiceDefinition findPublicServiceByName(Thing thing, String serviceName) {
        return findPublicServiceInList(listPublicServiceDefinitions(thing), serviceName);
    }

    /** Same name resolution as singular {@code service} facet. */
    static ServiceDefinition findPublicServiceInList(List<ServiceDefinition> publicServices, String serviceName) {
        if (serviceName == null) {
            return null;
        }
        for (ServiceDefinition sd : publicServices) {
            if (sd != null && sd.getName() != null && sd.getName().equalsIgnoreCase(serviceName)) {
                return sd;
            }
        }
        return null;
    }

    private static ObjectNode baseSuccess(String entityName, String facet, String scopeLabel) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        out.put("entityType", "Thing");
        out.put("entityName", entityName);
        out.put("facet", facet);
        out.put("scope", scopeLabel);
        return out;
    }

    private static ObjectNode propertyRowFull(PropertyDefinition pd) {
        ObjectNode row = DescribeEntitySchemaExecutor.compactPropertyRow(pd);
        putBoolField(row, pd, "isReadOnly", "readOnly");
        putBoolField(row, pd, "isPersistent", "persistent");
        putBoolField(row, pd, "isLogged", "logged");
        putBoolField(row, pd, "isIndexed", "indexed");
        return row;
    }

    private static void putBoolField(ObjectNode row, PropertyDefinition pd, String methodName, String jsonField) {
        try {
            java.lang.reflect.Method m = pd.getClass().getMethod(methodName);
            Object v = m.invoke(pd);
            if (v instanceof Boolean) {
                row.put(jsonField, (Boolean) v);
            }
        } catch (Exception ignored) {
            // optional platform fields
        }
    }

    private static PromptContextCacheSnapshot promptSnapshotOrNull() {
        com.thingworx.things.agent.AgentThing at = AgentToolContext.getAgentThing();
        return at != null ? at.getPromptContextSnapshot() : null;
    }

    private static boolean isRequiredParam(FieldDefinition fd) {
        try {
            if (fd.getAspects() == null) {
                return true;
            }
            Object v = fd.getAspects().getClass().getMethod("get", Object.class).invoke(fd.getAspects(), "isRequired");
            if (v instanceof Boolean) {
                return (Boolean) v;
            }
            if (v != null && "false".equalsIgnoreCase(v.toString())) {
                return false;
            }
        } catch (Exception ignored) {
            // default required
        }
        return true;
    }

    private static String aspectString(FieldDefinition fd, String key) {
        try {
            Object aspects = fd.getAspects();
            if (aspects == null) {
                return null;
            }
            Object v = aspects.getClass().getMethod("get", Object.class).invoke(aspects, key);
            return v != null ? v.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return null;
        }
        return n.asText();
    }

    private static ObjectNode recoveryHint(String tool, String argument) {
        ObjectNode rh = MAPPER.createObjectNode();
        rh.put("tool", tool);
        rh.put("argument", argument);
        return rh;
    }

    private static String errorJson(String code, String message, ObjectNode recoveryHint) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "error");
            o.put("code", code);
            o.put("message", message == null ? "" : message);
            if (recoveryHint != null) {
                o.set("recoveryHint", recoveryHint);
            }
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"code\":\"" + code + "\"}";
        }
    }
}
