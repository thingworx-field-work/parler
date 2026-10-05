package com.thingworx.things.agent.llm;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;

/**
 * Optional appendix appended to the agent {@linkplain com.thingworx.things.agent.AgentThing system prompt}
 * so the model receives tool-routing rules without relying on repo-only markdown (e.g. AGENT-CONTEXT.md).
 *
 * <p>Source text: {@code /com/thingworx/things/agent/llm_tool_routing_guide.txt} on the classpath.</p>
 */
public final class LlmRoutingGuide {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(LlmRoutingGuide.class);
    private static final String RESOURCE = "/com/thingworx/things/agent/llm_tool_routing_guide.txt";
    private static final String REPLAY_RESOURCE = "/com/thingworx/things/agent/llm_replay_format_routing_guide.txt";

    private static volatile String cached;
    private static volatile String cachedReplayGuide;

    private LlmRoutingGuide() {}

    /**
     * @param userSystemPrompt  from {@code AgentSettings.systemPrompt} or per-call override (may be blank)
     * @param append            when false, returns {@code userSystemPrompt} unchanged
     * @return combined system prompt for the LLM
     */
    public static String composeSystemPrompt(String userSystemPrompt, boolean append) {
        String base = userSystemPrompt != null ? userSystemPrompt.trim() : "";
        if (!append) {
            return base;
        }
        String guide = getBundledText();
        if (guide == null || guide.isBlank()) {
            return base;
        }
        guide = guide.trim();
        if (base.isEmpty()) {
            return guide;
        }
        return base + "\n\n---\n" + guide;
    }

    /**
     * Appends stable matrix + cohort routing instructions (Phase 2, {@code docs/agent/llm-token-budget.md}) after the
     * main tool routing guide when the agent is configured to append guides.
     */
    public static String appendReplayFormatRoutingGuide(String systemSoFar) {
        String g = getReplayBundledText();
        if (g == null || g.isBlank()) {
            return systemSoFar != null ? systemSoFar : "";
        }
        g = g.trim();
        String base = systemSoFar != null ? systemSoFar.trim() : "";
        if (base.isEmpty()) {
            return g;
        }
        return base + "\n\n---\n" + g;
    }

    private static String getBundledText() {
        String g = cached;
        if (g != null) {
            return g;
        }
        synchronized (LlmRoutingGuide.class) {
            if (cached != null) {
                return cached;
            }
            try (InputStream in = LlmRoutingGuide.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    LOG.warn("LlmRoutingGuide: missing classpath resource {}", RESOURCE);
                    cached = "";
                    return cached;
                }
                byte[] bytes = in.readAllBytes();
                cached = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("LlmRoutingGuide: failed to load {}: {}", RESOURCE, e.getMessage());
                cached = "";
            }
            return cached;
        }
    }

    private static String getReplayBundledText() {
        String g = cachedReplayGuide;
        if (g != null) {
            return g;
        }
        synchronized (LlmRoutingGuide.class) {
            if (cachedReplayGuide != null) {
                return cachedReplayGuide;
            }
            try (InputStream in = LlmRoutingGuide.class.getResourceAsStream(REPLAY_RESOURCE)) {
                if (in == null) {
                    LOG.warn("LlmRoutingGuide: missing classpath resource {}", REPLAY_RESOURCE);
                    cachedReplayGuide = "";
                    return cachedReplayGuide;
                }
                byte[] bytes = in.readAllBytes();
                cachedReplayGuide = new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.warn("LlmRoutingGuide: failed to load {}: {}", REPLAY_RESOURCE, e.getMessage());
                cachedReplayGuide = "";
            }
            return cachedReplayGuide;
        }
    }
}
