package com.thingworx.things.agent.llm.usage;

/** Helper report failure with stable error code (CC-7.6). */
public final class LlmUsageReportException extends Exception {

    private final String errorCode;

    public LlmUsageReportException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode != null ? errorCode : "REPORT_READ_FAILED";
    }

    public String getErrorCode() {
        return errorCode;
    }
}
