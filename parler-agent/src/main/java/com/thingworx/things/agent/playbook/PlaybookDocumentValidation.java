package com.thingworx.things.agent.playbook;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;
import org.slf4j.Logger;

import com.thingworx.things.agent.AgentThing;
import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.configrepo.ParlerPackageVersion;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.configrepo.ExtendedToolsManifest;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads.Kind;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * Shared parse / validate / report path for {@code ValidatePlaybookDocument} and repository authoring.
 */
public final class PlaybookDocumentValidation {

    private PlaybookDocumentValidation() {}

    public static String validateJson(String playbookJson, String packageDirId, AgentThing agent, Logger log) {
        String agentVersion = ParlerPackageVersion.fromAnchorClass(AgentThing.class);
        boolean hasJson = playbookJson != null && !playbookJson.isBlank();
        boolean hasPackage = packageDirId != null && !packageDirId.isBlank();
        if (hasJson == hasPackage) {
            return PlaybookValidationReport
                    .invalidRequest(
                            "exactly one of playbookJson or packageDirId must be non-blank (not both, not neither)",
                            agentVersion)
                    .toJson();
        }
        try {
            ValidationInputs inputs = resolveInputs(playbookJson, packageDirId, agent, log);
            if (inputs.errorReport != null) {
                return inputs.errorReport.toJson();
            }
            JSONObject rootProbe = new JSONObject(inputs.documentText.trim());
            PlaybookValidator.Result rootVal = PlaybookValidator.validatePlaybookJsonRoot(rootProbe);
            if (!rootVal.valid()) {
                return PlaybookValidationReportBuilder
                        .build(null, rootVal, packageDirId, agentVersion).toJson();
            }
            PlaybookDocument doc = PlaybookDocument.parse(inputs.documentText);
            List<ToolDefinition> tools = inputs.playbookToolDefs;
            ExtendedToolRegistrySnapshot ext = inputs.extendedTools;
            PlaybookValidator.Result docVal = PlaybookValidator.validateDocument(doc, inputs.packageDirId, tools, ext);
            return PlaybookValidationReportBuilder.build(doc, docVal, inputs.declaredPlaybookId, agentVersion)
                    .toJson();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return PlaybookValidationReport.invalidJson(msg, packageDirId, agentVersion).toJson();
        }
    }

    private static ValidationInputs resolveInputs(String playbookJson, String packageDirId, AgentThing agent,
            Logger log) throws Exception {
        Set<String> builtins = new HashSet<>(agent.builtinToolDefinitionNames());
        String repoName = agent.getConfigurationRepositoryThingName();
        ExtendedToolRegistrySnapshot ext = ExtendedToolRegistrySnapshot.missing();
        if (repoName != null && !repoName.isBlank()) {
            var fr = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
            if (fr.isPresent()) {
                RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(fr.get());
                ext = ExtendedToolsManifest.load(reader, agent.getName(), agent, builtins, log);
            }
        }
        List<ToolDefinition> tools = PlaybookToolDefinitionsMerge.merge(agent.toolRegistry(), ext);
        if (playbookJson != null && !playbookJson.isBlank()) {
            return new ValidationInputs(playbookJson, null, null, tools, ext, null);
        }
        String dirId = packageDirId.trim();
        if (repoName == null || repoName.isBlank()) {
            return ValidationInputs.error(PlaybookValidationReport.invalidRequest(
                    "configurationRepository is not configured; cannot read packageDirId", agentVersion(agent)));
        }
        var frOpt = FileRepositoryThingResolver.resolve(repoName.trim(), log, agent.getName());
        if (frOpt.isEmpty()) {
            return ValidationInputs.error(PlaybookValidationReport.invalidRequest(
                    "configurationRepository FileRepository unavailable", agentVersion(agent)));
        }
        RepositoryReader reader = FileRepositoryRepositoryReader.forConfiguration(frOpt.get());
        String path = PlaybookIds.playbookPathForId(dirId);
        RepositoryTextLoads.Result docRes = RepositoryTextLoads.loadText(reader, path);
        if (docRes.kind() != Kind.CONTENT) {
            return ValidationInputs.error(PlaybookValidationReport.packageUnreadable(dirId, path,
                    docRes.kind().name(), agentVersion(agent)));
        }
        return new ValidationInputs(docRes.text(), dirId, dirId, tools, ext, null);
    }

    private static String agentVersion(AgentThing agent) {
        return ParlerPackageVersion.fromAnchorClass(AgentThing.class);
    }

    private static final class ValidationInputs {
        final String documentText;
        final String packageDirId;
        final String declaredPlaybookId;
        final List<ToolDefinition> playbookToolDefs;
        final ExtendedToolRegistrySnapshot extendedTools;
        final PlaybookValidationReport errorReport;

        ValidationInputs(String documentText, String packageDirId, String declaredPlaybookId,
                List<ToolDefinition> playbookToolDefs, ExtendedToolRegistrySnapshot extendedTools,
                PlaybookValidationReport errorReport) {
            this.documentText = documentText;
            this.packageDirId = packageDirId;
            this.declaredPlaybookId = declaredPlaybookId;
            this.playbookToolDefs = playbookToolDefs;
            this.extendedTools = extendedTools;
            this.errorReport = errorReport;
        }

        static ValidationInputs error(PlaybookValidationReport report) {
            return new ValidationInputs(null, null, null, List.of(), ExtendedToolRegistrySnapshot.missing(), report);
        }
    }
}
