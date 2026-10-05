package com.thingworx.things.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured INFO logs for agent protection decisions (separate from {@link ParlerHitlAuditLog}). Never log secret
 * values — codes and identifiers only.
 *
 * @see docs/agent/protection.md §4.5
 */
public final class ParlerProtectionAudit {

    private static final Logger LOG = LoggerFactory.getLogger("com.thingworx.things.agent.ParlerProtectionAudit");

    private ParlerProtectionAudit() {}

    public static void blocked(String code, String toolName, String detailWithoutSecrets) {
        LOG.info("protection_blocked code={} tool={} detail={}",
                code == null ? "" : code,
                toolName == null ? "" : toolName,
                detailWithoutSecrets == null ? "" : detailWithoutSecrets);
    }
}
