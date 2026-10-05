package com.thingworx.things.agent.playbook;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.thingworx.things.Thing;

/**
 * Control flow for internal Playbook tool artifact rows on Parler AlwaysOn: a durable Stream append must run
 * before live wire emission is gated on {@code downlinkOk} (regression-tested in
 * {@code PlaybookInternalArtifactEmissionOrderTest}).
 */
public final class PlaybookInternalArtifactEmissionOrder {

    private PlaybookInternalArtifactEmissionOrder() {}

    /** {@code emitPlaybookInternalArtifactsForTool} no-ops when Parler stream wire ids are absent. */
    public static boolean hasParlerStreamWireIds(String requestId, String remoteThingName) {
        return requestId != null && remoteThingName != null && !requestId.isEmpty() && !remoteThingName.isEmpty();
    }

    public static boolean shouldEmitLiveParlerWire(Thing remoteConversation, AtomicBoolean downlinkOk) {
        return remoteConversation != null && downlinkOk != null && downlinkOk.get();
    }

    /**
     * Runs {@code appendStreamRow} first, then {@code emitLiveWire} only when {@code liveWireGate} is true.
     * Production supplies {@code () -> shouldEmitLiveParlerWire(rem, ok)} so downlink-false turns still persist.
     */
    public static void persistThenMaybeEmitParlerWire(
            Runnable appendStreamRow, BooleanSupplier liveWireGate, Runnable emitLiveWire) {
        appendStreamRow.run();
        if (liveWireGate.getAsBoolean()) {
            emitLiveWire.run();
        }
    }
}
