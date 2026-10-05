package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Regression tests: AlwaysOn slash must install a live {@code downlinkOk} handle on
 * {@link AgentToolContext}; Chat-style slash passes {@code null} and leaves the handle cleared.
 */
class PlaybookParlerStreamBindingsTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void bindForPlaybookSlash_setsDownlinkHandleWhenNonNull() {
        AtomicBoolean flag = new AtomicBoolean(false);
        PlaybookParlerStreamBindings.bindForPlaybookSlash("req-1", "RemoteThing", null, flag);
        assertEquals("req-1", AgentToolContext.getParlerRequestId());
        assertEquals("RemoteThing", AgentToolContext.getParlerRemoteThingName());
        assertSame(flag, AgentToolContext.getParlerDownlinkOk());
    }

    @Test
    void bindForPlaybookSlash_chatStyleDownlinkNull_clearsDownlinkHandle() {
        PlaybookParlerStreamBindings.bindForPlaybookSlash(null, null, null, null);
        assertNull(AgentToolContext.getParlerDownlinkOk());
    }
}
