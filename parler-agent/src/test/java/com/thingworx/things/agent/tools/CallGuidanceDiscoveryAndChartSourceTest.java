package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolDefinition;

/**
 * Class-level call guidance the model sees every turn. Both copies — the tool definition the model
 * receives and the routing guide — are asserted together so neither can drift back alone:
 *
 * <ul>
 *   <li>{@code invoke_service}: read a service's inputs from the tool that covers that target type
 *       before calling it, and bind the caller's stated constraints to that same call.</li>
 *   <li>{@code build_chart_from_tabular_result}: {@code last_invoke} is the latest qualifying table
 *       of the current request, only a returned cacheId is a cache handle, one turn may emit several
 *       charts, and an emitted chart is already done.</li>
 * </ul>
 *
 * These assertions prove the guidance is published and internally consistent. They cannot prove a
 * live model follows it — that stays with live acceptance.
 */
class CallGuidanceDiscoveryAndChartSourceTest {

    private static String routingGuide() throws Exception {
        try (InputStream in = CallGuidanceDiscoveryAndChartSourceTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ToolDefinition registered(String name) {
        ToolRegistry registry = new ToolRegistry();
        BuiltInTools.registerAll(registry);
        return registry.getAllDefinitions().stream()
                .filter(d -> name.equals(d.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not registered"));
    }

    private static String chartToolDescription() {
        return registered("build_chart_from_tabular_result").getDescription();
    }

    @Test
    void invokeServiceDescription_routesInputDiscoveryByTargetType() {
        String d = registered("invoke_service").getDescription();

        assertTrue(d.contains("Knowing a service **name** is not knowing its **inputs**"), d);
        assertTrue(d.contains("a concrete Thing → **discover_thing_members**"), d);
        assertTrue(d.contains("a ThingTemplate or ThingShape → **describe_entity_schema**"), d);

        // discover_thing_members resolves concrete Things only; no target may be disguised as one.
        assertTrue(d.contains("do not send a ThingTemplate or ThingShape to discover_thing_members"), d);
        assertTrue(d.contains("do not present some other entity type as a Thing"), d);

        // The visible set is config-dependent (get_service_definition is advertised when
        // advertiseLegacyServiceDiscoveryTools is on), so the text must not claim these two are the
        // only ones. It also must not name an executor-only tool: under default admission an
        // advertised description steering at an unadvertised name is a B7/S17 violation, which is
        // why this defers to "whichever your list offers" instead of enumerating the flag-on tool.
        assertFalse(d.contains("the only visible input-definition tools"), d);
        assertTrue(d.contains("advertised by default"), d);
        assertTrue(d.contains("use whichever ones your current tool list actually offers"), d);
        assertFalse(d.contains("get_service_definition"), d);

        // Targets no offered tool covers: clarify instead of guessing, off-list calls, or substituting.
        assertTrue(d.contains("When none of the tools you were given covers the target, say so and ask"), d);
        assertTrue(d.contains("calling a tool that is not in your list"), d);
        assertTrue(d.contains("running a broader substitute query"), d);

        // Known definitions and genuinely no-argument services stay callable without extra discovery.
        assertTrue(d.contains("Skip discovery when you already hold sufficient definitions"), d);
        assertTrue(d.contains("takes no arguments is still called with no parameters"), d);
    }

    @Test
    void invokeServiceDescription_bindsStatedConstraintsToThisCall() {
        String d = registered("invoke_service").getDescription();

        assertTrue(d.contains("Keep the entityType / entityName / serviceName you were pointed at"), d);
        assertTrue(d.contains("map the constraints the caller already stated"), d);
        assertTrue(d.contains("Omitting a scope or filter parameter widens the query"), d);
        assertTrue(d.contains("other optional parameters follow their own definitions"), d);
        assertTrue(d.contains("ask instead of inventing a key or dropping the constraint"), d);
        assertTrue(d.contains("Do not switch to a different entity because it exposes a service with the same name"), d);
        assertTrue(d.contains("do not split one constrained request into a separate query"), d);
    }

    @Test
    void routingGuide_carriesTheSameDiscoveryAndBindingRules() throws Exception {
        String g = routingGuide();

        assertTrue(g.contains("Knowing a service name does not establish its inputs"), "discovery rule");
        assertTrue(g.contains("For a concrete Thing, prefer `discover_thing_members`"), "Thing route");
        assertTrue(g.contains("For ThingTemplate/ThingShape metadata use `describe_entity_schema`"), "schema route");
        assertTrue(g.contains("When no advertised tool covers the target, say so and ask"), "no-tool case");
        assertTrue(g.contains("using a tool that supports the target entity type and is in your current tool list"),
                "config-scoped visibility");
        // The routing guide is not an advertised tool surface, so it may name the flag-gated tools as advertised-only.
        assertTrue(g.contains("advertised `discover_services` / `get_service_definition`"), "legacy path kept");
        assertTrue(g.contains("obtain parameter definitions unless already known"), "no repeat discovery");
        assertTrue(g.contains("A confirmed no-argument service needs no parameters"), "no-arg calls kept");
        assertTrue(g.contains("include all requested scope/time/filter constraints"), "scope binding");
        assertTrue(g.contains("split one constrained operation into broader calls"), "no splitting");
    }

    @Test
    void chartGuidance_statesLatestWinsAndDropsTheAmbiguityClause() throws Exception {
        String g = routingGuide();
        String d = chartToolDescription();

        // Bug 018: the resolver is latest-wins with no ambiguity branch, so the old clause sent the
        // model hunting for a cacheId that an inline source never has.
        assertFalse(g.contains("last_invoke becomes ambiguous"), "stale ambiguity clause still in routing guide");
        assertFalse(d.contains("last_invoke becomes ambiguous"), d);
        assertFalse(d.contains("Calling multiple builds all with `last_invoke` reuses the same table"), d);

        for (String text : new String[] {g, d}) {
            assertTrue(text.contains("qualifying tabular result of the"), text);
            assertTrue(text.contains("current request"), text);
            assertTrue(text.contains("latest-wins"), text);
            assertTrue(text.contains("make it ambiguous"), text);
        }
    }

    @Test
    void chartGuidance_saysWhatIsNotACacheId() throws Exception {
        String g = routingGuide();
        String d = chartToolDescription();

        assertTrue(g.contains("evidence ids (e1, e2, …), tool call ids and chartId are never cacheIds"), g);
        assertTrue(d.contains("Evidence ids (`e1`, `e2`, …), tool call ids and `chartId` are **not** cacheIds"), d);
        for (String text : new String[] {g, d}) {
            // A qualifying JSON single table now does get a real handle on its envelope, so neither text may
            // still tell the model such a source can never have one — that would steer it to drop the handle
            // it was just given and re-query.
            assertFalse(text.contains("no cacheId at all"), text);
            assertFalse(text.contains("inline JSON source has none"), text);
            assertTrue(text.contains("carry one on its outer envelope"), text);
            // The rule that survives: use a returned handle, never a manufactured one.
            assertTrue(text.contains("Use only a handle a result actually returned"), text);
            assertTrue(text.contains("never invent one"), text);
            assertTrue(text.contains("do not retry the same invalid id"), text);
        }
    }

    @Test
    void chartGuidance_allowsSeveralChartsPerTurnAndCountsEmittedOnesAsDone() throws Exception {
        String g = routingGuide();
        String d = chartToolDescription();

        for (String text : new String[] {g, d}) {
            assertTrue(text.contains("Query A → chart A → query B → chart B"), text);
            assertTrue(text.contains("CHART_EMITTED"), text);
            assertTrue(text.contains("count it as done"), text);
            assertTrue(text.contains("rebuild it"), text);
            // The live model invented a one-chart-per-turn limit; both copies now deny it.
            assertTrue(text.contains("one chart per turn"), text);
        }
    }

    @Test
    void chartGuidance_scopesLastInvokeToTheCurrentRequest() throws Exception {
        String g = routingGuide();
        String d = chartToolDescription();

        for (String text : new String[] {g, d}) {
            assertTrue(text.contains("new user request"), text);
            assertTrue(text.contains("history does not make them"), text);
            // The empty state is last_invoke only. A cacheId the caller still holds resolves before
            // the round-source check, so both texts must keep that exception — otherwise one of them
            // sends the model back through a redundant query and another approval.
            assertTrue(text.contains("you still hold"), text);
            assertTrue(text.contains("stays usable"), text);
        }
    }

    @Test
    void chartToolParameterSchemaIsUnchangedByTheWordingFix() {
        Map<String, Object> schema = registered("build_chart_from_tabular_result").getParametersSchema();
        Object props = schema.get("properties");
        assertTrue(props instanceof Map, "parameters schema still exposes properties");
        Map<?, ?> properties = (Map<?, ?>) props;
        for (String key : new String[] {"source", "cacheId", "kind", "intent", "xColumn", "yColumn"}) {
            assertTrue(properties.containsKey(key), key + " missing from parameters schema");
        }
    }
}
