package com.thingworx.things.agent;

import java.time.Instant;

import org.joda.time.DateTime;
import org.json.JSONObject;

import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinition;
import com.thingworx.metadata.annotations.ThingworxConfigurationTableDefinitions;
import com.thingworx.metadata.annotations.ThingworxDataShapeDefinition;
import com.thingworx.metadata.annotations.ThingworxFieldDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceDefinition;
import com.thingworx.metadata.annotations.ThingworxServiceParameter;
import com.thingworx.metadata.annotations.ThingworxServiceResult;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.llm.usage.LlmUsageHelperSupport;

/**
 * LLM usage report helper (CC-7 M0b). Reads AgentLlmCallStream; CSV export may write via FileRepository SaveText.
 */
@ThingworxConfigurationTableDefinitions(tables = {
        @ThingworxConfigurationTableDefinition(
                name = "Settings",
                description = "Helper settings",
                isMultiRow = false,
                dataShape = @ThingworxDataShapeDefinition(fields = {
                        @ThingworxFieldDefinition(
                                name = "usagePricesJson",
                                description = "Usage price table JSON",
                                baseType = "TEXT",
                                ordinal = 0)
                }))
})
public class AgentLlmUsageHelperThing extends Thing {

    private static final long serialVersionUID = 1L;

    @ThingworxServiceDefinition(
            name = "GetLlmUsageReport",
            description = "Returns JSON usage report for AgentLlmCallStream events in the requested time range.")
    @ThingworxServiceResult(name = "result", baseType = "JSON",
            description = "Usage report JSON")
    public JSONObject GetLlmUsageReport(
            @ThingworxServiceParameter(name = "StartTime", baseType = "DATETIME",
                    description = "Inclusive start of callStartedAt filter [StartTime, EndTime)",
                    aspects = {"isRequired:true"}) DateTime startTime,
            @ThingworxServiceParameter(name = "EndTime", baseType = "DATETIME",
                    description = "Exclusive end of callStartedAt filter [StartTime, EndTime)",
                    aspects = {"isRequired:true"}) DateTime endTime,
            @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
                    description = "Optional exact conversation filter") String conversationId,
            @ThingworxServiceParameter(name = "agentThing", baseType = "STRING",
                    description = "Optional exact AgentThing filter") String agentThing,
            @ThingworxServiceParameter(name = "model", baseType = "STRING",
                    description = "Optional exact requestedModel filter") String model) throws Exception {
        return LlmUsageHelperSupport.buildReportJson(
                startTime, endTime, conversationId, agentThing, model, loadUsagePricesJson());
    }

    @ThingworxServiceDefinition(
            name = "ExportLlmUsageCsv",
            description = "Exports one CSV row per callId and writes it to the selected FileRepository.")
    @ThingworxServiceResult(name = "result", baseType = "TEXT",
            description = "Usage CSV")
    public String ExportLlmUsageCsv(
            @ThingworxServiceParameter(name = "StartTime", baseType = "DATETIME",
                    description = "Inclusive start of callStartedAt filter [StartTime, EndTime)",
                    aspects = {"isRequired:true"}) DateTime startTime,
            @ThingworxServiceParameter(name = "EndTime", baseType = "DATETIME",
                    description = "Exclusive end of callStartedAt filter [StartTime, EndTime)",
                    aspects = {"isRequired:true"}) DateTime endTime,
            @ThingworxServiceParameter(name = "conversationId", baseType = "STRING",
                    description = "Optional exact conversation filter") String conversationId,
            @ThingworxServiceParameter(name = "agentThing", baseType = "STRING",
                    description = "Optional exact AgentThing filter") String agentThing,
            @ThingworxServiceParameter(name = "model", baseType = "STRING",
                    description = "Optional exact requestedModel filter") String model,
            @ThingworxServiceParameter(name = "FileRepository", baseType = "THINGNAME",
                    description = "Target FileRepository Thing",
                    aspects = {"thingTemplate:FileRepository", "defaultValue:SystemRepository"}) String fileRepository,
            @ThingworxServiceParameter(name = "FileName", baseType = "STRING",
                    description = "Repository-relative path; default placeholder expands to /LLM-USAGE{YYYYmmddHHMM}.csv",
                    aspects = {"defaultValue:/LLM-USAGE{YYYYmmddHHMM}.csv"}) String fileName) throws Exception {
        return LlmUsageHelperSupport.exportCsv(
                startTime,
                endTime,
                conversationId,
                agentThing,
                model,
                fileRepository,
                fileName,
                loadUsagePricesJson());
    }

    private String loadUsagePricesJson() {
        try {
            Object value = getConfigurationSetting("Settings", "usagePricesJson");
            return value != null ? String.valueOf(value) : "{\"version\":\"unpriced-v1\",\"prices\":[]}";
        } catch (Exception e) {
            return "{\"version\":\"unpriced-v1\",\"prices\":[]}";
        }
    }
}
