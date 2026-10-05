package com.thingworx.things.agent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.tools.PendingApprovalStore;

/**
 * Periodic sweep of {@link com.thingworx.things.agent.tools.PendingApprovalRecord} TTL; delivers
 * {@code approval.resolved} / {@code done} on expiry (wire shapes in {@code CONTRACTS/API_CONTRACT.md}).
 */
public final class ParlerApprovalExpiryScheduler {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ParlerApprovalExpiryScheduler.class);
    private static final long INITIAL_DELAY_SEC = 30;
    private static final long PERIOD_SEC = 30;

    private static volatile boolean started;

    private ParlerApprovalExpiryScheduler() {}

    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "parler-approval-expiry");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                int n = PendingApprovalStore.sweepExpired(now, AgentThing::deliverParlerApprovalExpired);
                if (n > 0) {
                    LOG.info("Parler approval expiry sweep removed {} pending record(s)", n);
                }
            } catch (Throwable t) {
                LOG.warn("Parler approval expiry sweep failed: {}", t.getMessage());
            }
        }, INITIAL_DELAY_SEC, PERIOD_SEC, TimeUnit.SECONDS);
    }
}
