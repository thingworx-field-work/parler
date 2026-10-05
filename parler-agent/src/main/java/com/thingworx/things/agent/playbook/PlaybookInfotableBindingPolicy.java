package com.thingworx.things.agent.playbook;

import java.util.Map;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.metadata.ServiceDefinition;
import com.thingworx.things.agent.configrepo.ExtendedToolRegistrySnapshot;
import com.thingworx.things.agent.tools.InfotableJsonCodec;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * Fail-closed rules for Playbook {@code $infotable} bindings: repository extended tools only (v1).
 */
public final class PlaybookInfotableBindingPolicy {

    private PlaybookInfotableBindingPolicy() {}

    /**
     * Playbook expression binding capabilities for operator snapshot ({@code playbookRuntime.bindings}).
     */
    public static PlaybookBindingCapabilities bindingCapabilities() {
        return PlaybookBindingCapabilities.v1();
    }

    public static boolean isRepositoryExtendedTool(String tool, ExtendedToolRegistrySnapshot ext) {
        if (tool == null || tool.isBlank() || ext == null || ext.isFileInvalid()) {
            return false;
        }
        return ext.find(tool).isPresent();
    }

    public static void rejectInfotableArgsUnlessExtendedTool(String tool, ExtendedToolRegistrySnapshot ext,
            Map<String, InfoTable> infotableArgs) throws PlaybookRunException {
        if (infotableArgs == null || infotableArgs.isEmpty()) {
            return;
        }
        if (!isRepositoryExtendedTool(tool, ext)) {
            throw new PlaybookRunException(
                    "INFOTABLE binding is only supported for playbook-safe extended tools: " + tool,
                    "PLAYBOOK_TOOL_NOT_EXTENDED");
        }
    }

    public static void validateInfotableParameterShapes(ServiceDefinition sd, Map<String, InfoTable> tables)
            throws PlaybookRunException {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        for (Map.Entry<String, InfoTable> e : tables.entrySet()) {
            String pname = e.getKey();
            InfoTable val = e.getValue();
            FieldDefinition fd = sd.getParameters() != null ? sd.getParameters().get(pname) : null;
            if (fd == null || fd.getBaseType() != BaseTypes.INFOTABLE) {
                throw new PlaybookRunException("INFOTABLE binding for parameter that is not INFOTABLE: " + pname,
                        "TABLE_REF_NOT_INFOTABLE_PARAM");
            }
            DataShapeDefinition expected = InfotableJsonCodec.resolveDataShapeForParameter(fd);
            DataShapeDefinition actual = val.getDataShape();
            String expName = expected != null ? expected.getName() : null;
            String actName = actual != null ? actual.getName() : null;
            if (expName != null && !expName.isBlank() && actName != null && !actName.isBlank()
                    && !expName.equals(actName)) {
                throw new PlaybookRunException("DataShape mismatch for parameter " + pname + ": expected " + expName
                        + " but table has " + actName, "TABLE_REF_DATASHAPE_MISMATCH");
            }
        }
    }
}
