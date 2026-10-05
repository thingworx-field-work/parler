package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class TaskStateErrorMapperTest {

    @Test
    void maps_top_level_code_upper_snake() {
        assertEquals(TaskStateErrorCode.ENTITY_NOT_FOUND,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"ENTITY_NOT_FOUND\"}")));
    }

    @Test
    void maps_hyphenated_code_to_enum() {
        assertEquals(TaskStateErrorCode.ENTITY_NOT_FOUND,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"entity-not-found\"}")));
    }

    @Test
    void maps_one_level_error_code() {
        JSONObject root = new JSONObject();
        root.put("error", new JSONObject().put("code", "PERMISSION_DENIED"));
        assertEquals(TaskStateErrorCode.PERMISSION_DENIED, TaskStateErrorMapper.mapFromToolJson(root));
    }

    @Test
    void deeper_error_cause_code_is_unknown() {
        JSONObject root = new JSONObject();
        root.put("error", new JSONObject().put("cause", new JSONObject().put("code", "ENTITY_NOT_FOUND")));
        assertEquals(TaskStateErrorCode.UNKNOWN, TaskStateErrorMapper.mapFromToolJson(root));
    }

    @Test
    void maps_invalid_parameters_legacy_to_parameter_invalid() {
        assertEquals(TaskStateErrorCode.PARAMETER_INVALID,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"INVALID_PARAMETERS\"}")));
        assertEquals(TaskStateErrorCode.PARAMETER_INVALID,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"invalid-parameters\"}")));
    }

    @Test
    void maps_extended_error_codes() {
        assertEquals(TaskStateErrorCode.CACHE_MISS,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"CACHE_MISS\"}")));
        assertEquals(TaskStateErrorCode.INVALID_TIME_RANGE,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"INVALID_TIME_RANGE\"}")));
        assertEquals(TaskStateErrorCode.SERVICE_LOOKUP_FAILED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"SERVICE_LOOKUP_FAILED\"}")));
        assertEquals(TaskStateErrorCode.PROPERTY_METADATA_UNRESOLVED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"PROPERTY_METADATA_UNRESOLVED\"}")));
        assertEquals(TaskStateErrorCode.PROTECTED_VALUE_READ_BLOCKED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"PROTECTED_VALUE_READ_BLOCKED\"}")));
        assertEquals(TaskStateErrorCode.PROTECTED_VALUE_WRITE_BLOCKED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"PROTECTED_VALUE_WRITE_BLOCKED\"}")));
        assertEquals(TaskStateErrorCode.PROTECTED_VALUE_INPUT_BLOCKED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"PROTECTED_VALUE_INPUT_BLOCKED\"}")));
        assertEquals(TaskStateErrorCode.IDENTITY_RESOLUTION_REQUIRED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"IDENTITY_RESOLUTION_REQUIRED\"}")));
        assertEquals(TaskStateErrorCode.THINGNAME_VALUE_REQUIRED,
                TaskStateErrorMapper.mapFromToolJson(new JSONObject("{\"code\":\"THINGNAME_VALUE_REQUIRED\"}")));
    }
}
