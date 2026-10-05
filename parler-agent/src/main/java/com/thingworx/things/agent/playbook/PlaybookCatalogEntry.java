package com.thingworx.things.agent.playbook;

import org.json.JSONObject;

/** Derived runtime metadata for one loaded playbook (from merged {@code playbook.json}). */
public final class PlaybookCatalogEntry {

    private final String id;
    private final String title;
    private final String description;
    private final String whenToUse;
    private final String playbookPath;
    private final JSONObject inputSchema;
    private final JSONObject execution;

    public PlaybookCatalogEntry(String id, String title, String description, String whenToUse,
            String playbookPath, JSONObject inputSchema, JSONObject execution) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.whenToUse = whenToUse;
        this.playbookPath = playbookPath;
        this.inputSchema = inputSchema;
        this.execution = execution;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public String whenToUse() {
        return whenToUse;
    }

    public String playbookPath() {
        return playbookPath;
    }

    public JSONObject inputSchema() {
        return inputSchema;
    }

    public JSONObject execution() {
        return execution;
    }
}
