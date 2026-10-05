package com.thingworx.things.agent.taskstate;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillChecklistParserTest {

    private static final String FENCE_A =
            "```parler-task-checklist-v1\n"
                    + "{\n"
                    + "  \"schemaVersion\": 1,\n"
                    + "  \"requiredEvidence\": [\n"
                    + "    { \"id\": \"a\", \"description\": \"First\", \"tool\": \"invoke_service\", \"match\": { \"targetName\": \"T\" } }\n"
                    + "  ]\n"
                    + "}\n"
                    + "```";

    private static final String FENCE_B =
            "```parler-task-checklist-v1\n"
                    + "{\n"
                    + "  \"schemaVersion\": 1,\n"
                    + "  \"requiredEvidence\": [\n"
                    + "    { \"id\": \"b\", \"description\": \"Second\" }\n"
                    + "  ]\n"
                    + "}\n"
                    + "```";

    @Test
    void unionConcatenatesBodiesInOrder() throws SkillChecklistParseException {
        JSONObject u = SkillChecklistParser.unionChecklists(List.of(FENCE_A, FENCE_B));
        Assertions.assertNotNull(u);
        Assertions.assertEquals(1, u.optInt("schemaVersion"));
        Assertions.assertEquals(2, u.getJSONArray("requiredEvidence").length());
        Assertions.assertEquals("a", u.getJSONArray("requiredEvidence").getJSONObject(0).getString("id"));
        Assertions.assertEquals("b", u.getJSONArray("requiredEvidence").getJSONObject(1).getString("id"));
    }

    @Test
    void duplicateIdAcrossBodiesFails() {
        String dup =
                "```parler-task-checklist-v1\n"
                        + "{\n"
                        + "  \"schemaVersion\": 1,\n"
                        + "  \"requiredEvidence\": [\n"
                        + "    { \"id\": \"a\", \"description\": \"Dup\" }\n"
                        + "  ]\n"
                        + "}\n"
                        + "```";
        Assertions.assertThrows(SkillChecklistParseException.class,
                () -> SkillChecklistParser.unionChecklists(List.of(FENCE_A, dup)));
    }

    @Test
    void unknownTopLevelKeyFails() {
        String bad =
                "```parler-task-checklist-v1\n"
                        + "{\n"
                        + "  \"schemaVersion\": 1,\n"
                        + "  \"extra\": true,\n"
                        + "  \"requiredEvidence\": []\n"
                        + "}\n"
                        + "```";
        Assertions.assertThrows(SkillChecklistParseException.class,
                () -> SkillChecklistParser.unionChecklists(List.of(bad)));
    }

    @Test
    void invalidCardinalityFails() {
        String bad =
                "```parler-task-checklist-v1\n"
                        + "{\n"
                        + "  \"schemaVersion\": 1,\n"
                        + "  \"requiredEvidence\": [\n"
                        + "    { \"id\": \"x\", \"description\": \"d\", \"cardinality\": \"many\" }\n"
                        + "  ]\n"
                        + "}\n"
                        + "```";
        Assertions.assertThrows(SkillChecklistParseException.class,
                () -> SkillChecklistParser.unionChecklists(List.of(bad)));
    }

    @Test
    void unknownMatchKeyFails() {
        String bad =
                "```parler-task-checklist-v1\n"
                        + "{\n"
                        + "  \"schemaVersion\": 1,\n"
                        + "  \"requiredEvidence\": [\n"
                        + "    { \"id\": \"x\", \"description\": \"d\", \"match\": { \"targetNmae\": \"oops\" } }\n"
                        + "  ]\n"
                        + "}\n"
                        + "```";
        Assertions.assertThrows(SkillChecklistParseException.class,
                () -> SkillChecklistParser.unionChecklists(List.of(bad)));
    }

    @Test
    void invalidKindFails() {
        String bad =
                "```parler-task-checklist-v1\n"
                        + "{\n"
                        + "  \"schemaVersion\": 1,\n"
                        + "  \"requiredEvidence\": [\n"
                        + "    { \"id\": \"x\", \"description\": \"d\", \"kind\": \"evidnce\", \"tool\": \"invoke_service\" }\n"
                        + "  ]\n"
                        + "}\n"
                        + "```";
        Assertions.assertThrows(SkillChecklistParseException.class,
                () -> SkillChecklistParser.unionChecklists(List.of(bad)));
    }
}
