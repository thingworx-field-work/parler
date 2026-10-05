package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.tools.InvokeServiceDatetimeLiteralDefense.RejectionReason;

/**
 * Thrown by {@link InvokeServiceDatetimeLiteralDefense} when a raw text value supplied for a DATETIME-shaped
 * parameter slot of {@code invoke_service} is rejected before Joda parsing
 * ({@code docs/agent/time-interpretation.md} §7 {@code UNSUPPORTED_RELATIVE_LITERAL}).
 *
 * <p>A string-prefixed {@code IllegalArgumentException} would be masked by the catch-all wrapping in
 * {@link InvokeServiceExecutor}'s {@code jsonToPrimitive} and never reach the wire as
 * {@code UNSUPPORTED_RELATIVE_LITERAL} (it would surface as {@code INVALID_PARAMETERS}). A typed exception
 * lets the executor route the rejection deterministically and lets call-site logging include the parameter name,
 * raw value, and a stable {@link RejectionReason} classifier for telemetry-driven expansion.</p>
 *
 * <p>Extends {@link IllegalArgumentException} so existing higher-level catch sites (e.g. callers that only know
 * about {@link IllegalArgumentException}) still degrade safely; the typed catch in
 * {@link InvokeServiceExecutor#doInvokeService} runs <em>before</em> the broad {@link IllegalArgumentException}
 * catch.</p>
 */
public final class UnsupportedRelativeLiteralException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String paramName;
    private final String rawValue;
    private final RejectionReason rejectionReason;
    private final String detail;

    public UnsupportedRelativeLiteralException(String paramName, String rawValue,
            RejectionReason rejectionReason, String detail) {
        super(detail);
        this.paramName = paramName;
        this.rawValue = rawValue;
        this.rejectionReason = rejectionReason;
        this.detail = detail;
    }

    public String getParamName() {
        return paramName;
    }

    public String getRawValue() {
        return rawValue;
    }

    /**
     * Stable, telemetry-friendly classifier. Distinct from {@link #getDetail()} which is
     * an English message intended for the LLM / wire payload.
     */
    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }

    public String getDetail() {
        return detail;
    }
}
