package com.thingworx.things.agent.quality;

/**
 * U4 G6 quality finding severity. {@link #BLOCKING} findings are mandatory inputs to downstream
 * analysis evidence; presentation paths may still render with explicit warnings.
 */
public enum QualitySeverity {
    INFO,
    WARNING,
    BLOCKING
}
