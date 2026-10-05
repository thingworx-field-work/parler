package com.thingworx.things.agent.time;

import java.time.Instant;
import java.util.Objects;

/** Result of {@link ParlerTimeResolver} — closed-open range {@code [startUtc, endUtc)} when successful. */
public final class ParlerTimeResolution {

    private final boolean success;
    private final Instant startUtc;
    private final Instant endUtc;
    private final ParlerTimeErrorCode errorCode;
    private final String errorMessage;

    private ParlerTimeResolution(boolean success, Instant startUtc, Instant endUtc, ParlerTimeErrorCode errorCode,
            String errorMessage) {
        this.success = success;
        this.startUtc = startUtc;
        this.endUtc = endUtc;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static ParlerTimeResolution okClosedOpenRange(Instant startUtc, Instant endUtc) {
        Objects.requireNonNull(startUtc, "startUtc");
        Objects.requireNonNull(endUtc, "endUtc");
        return new ParlerTimeResolution(true, startUtc, endUtc, null, null);
    }

    public static ParlerTimeResolution failure(ParlerTimeErrorCode code, String message) {
        Objects.requireNonNull(code, "code");
        return new ParlerTimeResolution(false, null, null, code, message != null ? message : "");
    }

    public boolean isSuccess() {
        return success;
    }

    public Instant getStartUtc() {
        return startUtc;
    }

    public Instant getEndUtc() {
        return endUtc;
    }

    public ParlerTimeErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
