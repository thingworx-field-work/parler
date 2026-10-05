package com.thingworx.things.agent.playbook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

import com.thingworx.types.InfoTable;

/** Resolved {@code tool_call.args}: JSON-serializable scalars/objects plus side-channel INFOTABLE parameters. */
public final class PlaybookResolvedToolArgs {

    private final JSONObject jsonArgs;
    private final Map<String, InfoTable> infotableArgs;

    public PlaybookResolvedToolArgs(JSONObject jsonArgs, Map<String, InfoTable> infotableArgs) {
        this.jsonArgs = jsonArgs != null ? jsonArgs : new JSONObject();
        this.infotableArgs = infotableArgs != null ? infotableArgs : Collections.emptyMap();
    }

    public JSONObject jsonArgs() {
        return jsonArgs;
    }

    public Map<String, InfoTable> infotableArgs() {
        return infotableArgs;
    }

    public static PlaybookResolvedToolArgs empty() {
        return new PlaybookResolvedToolArgs(new JSONObject(), new LinkedHashMap<>());
    }
}
