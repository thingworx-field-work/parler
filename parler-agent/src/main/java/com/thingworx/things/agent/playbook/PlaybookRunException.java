package com.thingworx.things.agent.playbook;

/** Playbook execution failure surfaced to callers. */
public final class PlaybookRunException extends Exception {

    private final String failureCode;

    public PlaybookRunException(String message) {
        this(message, null);
    }

    public PlaybookRunException(String message, String failureCode) {
        super(message);
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
