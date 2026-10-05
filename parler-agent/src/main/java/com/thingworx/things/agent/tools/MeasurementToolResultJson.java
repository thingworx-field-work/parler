package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptorSupport;
import com.thingworx.things.agent.transform.time.MeasurementRunResult;

/**
 * Success shell of the computing-enhancement measurement modes: the U4 shell plus root {@code warnings}
 * and {@code completeness}. Both are egress priority fields, so the scope of the numbers reaches the model
 * even when the envelope is compacted away.
 */
final class MeasurementToolResultJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MeasurementToolResultJson() {}

    static String success(MeasurementRunResult run) throws Exception {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "OK");
        out.put("reason", run.envelope().metrics().get("outcome"));
        if (run.findingCacheId() != null) {
            out.put("findingCacheId", run.findingCacheId());
        }
        out.put("mayPublish", run.mayPublish());
        ArrayNode warnings = out.putArray("warnings");
        run.envelope().evidence().warnings().forEach(warnings::add);
        SourceDescriptor descriptor = run.descriptor();
        if (descriptor != null) {
            SourceDescriptorSupport.putPublicEnvelopeFields(out, descriptor);
        } else {
            ObjectNode completeness = out.putObject("completeness");
            completeness.put("status", SourceDescriptor.CompletenessStatus.UNKNOWN.name());
            completeness.putArray("reasons");
        }
        out.set("analysisEnvelope", MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(run.envelope())));
        return MAPPER.writeValueAsString(out);
    }
}
