package com.thingworx.things.agent.llm.ratecontrol;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.IntegerPrimitive;
import com.thingworx.types.primitives.LongPrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * INFOTABLE builders for Provider rate-control services ({@code docs/agent/rate-control.md} §12).
 */
public final class RateControlInfoTables {

    private RateControlInfoTables() {}

    public static InfoTable stateRow(LLMAPIProviderRateGate.GateSnapshot snap) {
        InfoTable t = new InfoTable(stateDataShape());
        ValueCollection row = new ValueCollection();
        row.put("mode", new StringPrimitive(snap.mode.name()));
        row.put("tokenReserveStrategy", new StringPrimitive(snap.tokenReserveStrategy.name()));
        row.put("tokensPerMinuteLimit", new IntegerPrimitive(snap.tokensPerMinuteLimit));
        row.put("requestsPerMinuteLimit", new IntegerPrimitive(snap.requestsPerMinuteLimit));
        row.put("maxConcurrentRequests", new IntegerPrimitive(snap.maxConcurrentRequests));
        row.put("maxLocalWaitMs", new IntegerPrimitive(snap.maxLocalWaitMs));
        row.put("maxSingleRequestTokens", new IntegerPrimitive(snap.maxSingleRequestTokens));
        row.put("estimateSafetyMultiplier", new NumberPrimitive(snap.estimateSafetyMultiplier));
        row.put("tokensRemaining", new NumberPrimitive(snap.tokensRemaining));
        row.put("requestsRemaining", new NumberPrimitive(snap.requestsRemaining));
        row.put("inflight", new IntegerPrimitive(snap.inflight));
        long nowMs = System.currentTimeMillis();
        if (snap.blockedUntilEpochMs > nowMs) {
            row.put("blockedUntil", new DatetimePrimitive(snap.blockedUntilEpochMs));
        }
        row.put("retryAfterMs", new LongPrimitive(snap.retryAfterMs));
        row.put("lastRejectionReason", new StringPrimitive(
                snap.lastRejectionReason != null ? snap.lastRejectionReason.wireValue() : ""));
        row.put("localAdmissions", new LongPrimitive(snap.localAdmissions));
        row.put("localWaits", new LongPrimitive(snap.localWaits));
        row.put("localRejections", new LongPrimitive(snap.localRejections));
        row.put("upstream429s", new LongPrimitive(snap.upstream429s));
        row.put("maxWaitMs", new LongPrimitive(snap.maxWaitMs));
        row.put("lastSafeHeaderSnapshot", new StringPrimitive(snap.lastSafeHeaderSnapshot != null ? snap.lastSafeHeaderSnapshot : ""));
        t.addRow(row);
        return t;
    }

    public static InfoTable resetResult(boolean success, String message, int inflight) {
        InfoTable t = new InfoTable(resetDataShape());
        ValueCollection row = new ValueCollection();
        row.put("success", new BooleanPrimitive(success));
        row.put("message", new StringPrimitive(message != null ? message : ""));
        row.put("inflight", new IntegerPrimitive(inflight));
        t.addRow(row);
        return t;
    }

    public static InfoTable testAdmissionRow(
            boolean wouldAllow,
            LlmRateLimitAdmissionReason reason,
            RateEstimate estimate,
            RateControlConfig config,
            double tokensRemaining,
            double requestsRemaining,
            int inflight,
            long retryAfterMs) {
        InfoTable t = new InfoTable(testAdmissionDataShape());
        ValueCollection row = new ValueCollection();
        row.put("wouldAllow", new BooleanPrimitive(wouldAllow));
        row.put("reason", new StringPrimitive(reason != null ? reason.wireValue() : ""));
        row.put("estimatedInputTokens", new LongPrimitive(estimate.estimatedInputTokens));
        row.put("requestedMaxOutputTokens", new LongPrimitive(estimate.requestedMaxOutputTokens));
        row.put("reservedTokens", new LongPrimitive(estimate.reservedTokens));
        row.put("tokensPerMinuteLimit", new IntegerPrimitive(config.tokensPerMinuteLimit));
        row.put("requestsPerMinuteLimit", new IntegerPrimitive(config.requestsPerMinuteLimit));
        row.put("maxConcurrentRequests", new IntegerPrimitive(config.maxConcurrentRequests));
        row.put("tokensRemaining", new NumberPrimitive(tokensRemaining));
        row.put("requestsRemaining", new NumberPrimitive(requestsRemaining));
        row.put("inflight", new IntegerPrimitive(inflight));
        row.put("retryAfterMs", new LongPrimitive(retryAfterMs));
        t.addRow(row);
        return t;
    }

    private static DataShapeDefinition stateDataShape() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        int o = 0;
        dsd.addFieldDefinition(stringField("mode", o++));
        dsd.addFieldDefinition(stringField("tokenReserveStrategy", o++));
        dsd.addFieldDefinition(intField("tokensPerMinuteLimit", o++));
        dsd.addFieldDefinition(intField("requestsPerMinuteLimit", o++));
        dsd.addFieldDefinition(intField("maxConcurrentRequests", o++));
        dsd.addFieldDefinition(intField("maxLocalWaitMs", o++));
        dsd.addFieldDefinition(intField("maxSingleRequestTokens", o++));
        dsd.addFieldDefinition(numberField("estimateSafetyMultiplier", o++));
        dsd.addFieldDefinition(numberField("tokensRemaining", o++));
        dsd.addFieldDefinition(numberField("requestsRemaining", o++));
        dsd.addFieldDefinition(intField("inflight", o++));
        FieldDefinition blocked = new FieldDefinition();
        blocked.setName("blockedUntil");
        blocked.setBaseType(BaseTypes.DATETIME);
        blocked.setOrdinal(o++);
        dsd.addFieldDefinition(blocked);
        FieldDefinition retry = new FieldDefinition();
        retry.setName("retryAfterMs");
        retry.setBaseType(BaseTypes.LONG);
        retry.setOrdinal(o++);
        dsd.addFieldDefinition(retry);
        dsd.addFieldDefinition(stringField("lastRejectionReason", o++));
        FieldDefinition la = new FieldDefinition();
        la.setName("localAdmissions");
        la.setBaseType(BaseTypes.LONG);
        la.setOrdinal(o++);
        dsd.addFieldDefinition(la);
        FieldDefinition lw = new FieldDefinition();
        lw.setName("localWaits");
        lw.setBaseType(BaseTypes.LONG);
        lw.setOrdinal(o++);
        dsd.addFieldDefinition(lw);
        FieldDefinition lr = new FieldDefinition();
        lr.setName("localRejections");
        lr.setBaseType(BaseTypes.LONG);
        lr.setOrdinal(o++);
        dsd.addFieldDefinition(lr);
        FieldDefinition u = new FieldDefinition();
        u.setName("upstream429s");
        u.setBaseType(BaseTypes.LONG);
        u.setOrdinal(o++);
        dsd.addFieldDefinition(u);
        FieldDefinition mw = new FieldDefinition();
        mw.setName("maxWaitMs");
        mw.setBaseType(BaseTypes.LONG);
        mw.setOrdinal(o++);
        dsd.addFieldDefinition(mw);
        dsd.addFieldDefinition(stringField("lastSafeHeaderSnapshot", o));
        return dsd;
    }

    private static DataShapeDefinition resetDataShape() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        FieldDefinition success = new FieldDefinition();
        success.setName("success");
        success.setBaseType(BaseTypes.BOOLEAN);
        success.setOrdinal(0);
        dsd.addFieldDefinition(success);
        dsd.addFieldDefinition(stringField("message", 1));
        dsd.addFieldDefinition(intField("inflight", 2));
        return dsd;
    }

    private static DataShapeDefinition testAdmissionDataShape() {
        DataShapeDefinition dsd = new DataShapeDefinition();
        int o = 0;
        FieldDefinition allow = new FieldDefinition();
        allow.setName("wouldAllow");
        allow.setBaseType(BaseTypes.BOOLEAN);
        allow.setOrdinal(o++);
        dsd.addFieldDefinition(allow);
        dsd.addFieldDefinition(stringField("reason", o++));
        FieldDefinition eIn = new FieldDefinition();
        eIn.setName("estimatedInputTokens");
        eIn.setBaseType(BaseTypes.LONG);
        eIn.setOrdinal(o++);
        dsd.addFieldDefinition(eIn);
        FieldDefinition eOut = new FieldDefinition();
        eOut.setName("requestedMaxOutputTokens");
        eOut.setBaseType(BaseTypes.LONG);
        eOut.setOrdinal(o++);
        dsd.addFieldDefinition(eOut);
        FieldDefinition res = new FieldDefinition();
        res.setName("reservedTokens");
        res.setBaseType(BaseTypes.LONG);
        res.setOrdinal(o++);
        dsd.addFieldDefinition(res);
        dsd.addFieldDefinition(intField("tokensPerMinuteLimit", o++));
        dsd.addFieldDefinition(intField("requestsPerMinuteLimit", o++));
        dsd.addFieldDefinition(intField("maxConcurrentRequests", o++));
        dsd.addFieldDefinition(numberField("tokensRemaining", o++));
        dsd.addFieldDefinition(numberField("requestsRemaining", o++));
        dsd.addFieldDefinition(intField("inflight", o++));
        FieldDefinition retry = new FieldDefinition();
        retry.setName("retryAfterMs");
        retry.setBaseType(BaseTypes.LONG);
        retry.setOrdinal(o);
        dsd.addFieldDefinition(retry);
        return dsd;
    }

    private static FieldDefinition stringField(String name, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(BaseTypes.STRING);
        fd.setOrdinal(ordinal);
        return fd;
    }

    private static FieldDefinition intField(String name, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(BaseTypes.INTEGER);
        fd.setOrdinal(ordinal);
        return fd;
    }

    private static FieldDefinition numberField(String name, int ordinal) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(BaseTypes.NUMBER);
        fd.setOrdinal(ordinal);
        return fd;
    }
}
