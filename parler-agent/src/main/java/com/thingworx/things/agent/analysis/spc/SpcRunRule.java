package com.thingworx.things.agent.analysis.spc;

/** Versioned minimal I-MR run-rule catalog (§7.3). Default enabled: R1 only. */
public enum SpcRunRule {
    R1("spc_run_r1", "1", "one point beyond 3σ", true),
    R2("spc_run_r2", "1", "two of three consecutive beyond 2σ same side", false),
    R3("spc_run_r3", "1", "four of five consecutive beyond 1σ same side", false),
    R4("spc_run_r4", "1", "eight consecutive on one side of center", false);

    private final String methodId;
    private final String version;
    private final String description;
    private final boolean enabledByDefault;

    SpcRunRule(String methodId, String version, String description, boolean enabledByDefault) {
        this.methodId = methodId;
        this.version = version;
        this.description = description;
        this.enabledByDefault = enabledByDefault;
    }

    public String methodId() {
        return methodId;
    }

    public String version() {
        return version;
    }

    public String description() {
        return description;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }
}
