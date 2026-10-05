package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParlerGatewayUserStopTombstoneRegistryTest {

    @Test
    void tombstoneActiveUntilExpiry() {
        String cid = "gw-tomb-a";
        String rid = "req-tomb-a";
        String principal = "alice";
        String agent = "AgentThingA";
        assertFalse(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(cid, rid, principal, agent));
        ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(cid, rid, principal, agent);
        assertTrue(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(cid, rid, principal, agent));
    }

    @Test
    void noteSweepRemovesUnrelatedExpiredEntries() {
        String cid = "gw-sweep-main";
        String rid = "req-sweep-main";
        String principal = "bob";
        String agent = "AgentB";
        ParlerGatewayUserStopTombstoneRegistry.putExpiryMillisForTests("gw-orphan-a", "r-oa", "p1", "A1",
                System.currentTimeMillis() - 60_000L);
        ParlerGatewayUserStopTombstoneRegistry.putExpiryMillisForTests("gw-orphan-b", "r-ob", "p2", "A2",
                System.currentTimeMillis() - 60_000L);
        ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(cid, rid, principal, agent);
        assertFalse(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive("gw-orphan-a", "r-oa", "p1", "A1"));
        assertFalse(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive("gw-orphan-b", "r-ob", "p2", "A2"));
        assertTrue(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive(cid, rid, principal, agent));
    }

    @Test
    void nullConversationOrRequestDoesNotNote() {
        ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal(null, "r", "p", "a");
        ParlerGatewayUserStopTombstoneRegistry.noteGatewayUserStopTerminal("c", null, "p", "a");
        assertFalse(ParlerGatewayUserStopTombstoneRegistry.isGatewayUserStopTerminalActive("c", "r", "p", "a"));
    }
}
