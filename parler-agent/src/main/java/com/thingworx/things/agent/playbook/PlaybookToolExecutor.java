package com.thingworx.things.agent.playbook;

import java.util.Map;

import org.json.JSONObject;

import com.thingworx.types.InfoTable;

/** Executes playbook {@code tool_call} nodes through the normal Agent tool path. */
@FunctionalInterface
public interface PlaybookToolExecutor {

    PlaybookToolExecutionResult execute(String toolName, JSONObject jsonArgs, Map<String, InfoTable> infotableArgs)
            throws Exception;
}
