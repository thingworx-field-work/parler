package com.thingworx.things.agent.transform.time;

/** Whole-request failure of a computing-enhancement measurement, with a stable reason code. */
public class MeasurementException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public MeasurementException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
