package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.hostcontext.HostContextTemplateRegistry;
import com.thingworx.things.agent.hostcontext.HostContextTestTemplates;
import com.thingworx.things.agent.hostcontext.HostContextUplink;

class RejectReasonTest {

    @Test
    void hostContextUplink_reject_codes_match_allowlist() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
        HostContextTestTemplates.installBuiltInLikeTestTemplates();
        TreeSet<String> seen = new TreeSet<>();
        collect(seen, HostContextUplink.evaluate(null));
        collect(seen, HostContextUplink.evaluate(""));
        collect(seen, HostContextUplink.evaluate("   "));
        collect(seen, HostContextUplink.evaluate("{}"));
        collect(seen, HostContextUplink.evaluate("{\"context\":{}}"));
        collect(seen, HostContextUplink.evaluate("{\"key\":\"asset_detail.current_asset\",\"context\":\"bad\"}"));
        collect(seen, HostContextUplink.evaluate("{\"key\":\"asset_detail.current_asset\",\"context\":{}}"));
        String prefix = "{\"key\":\"asset_detail.current_asset\",\"context\":{\"thingName\":\"";
        String suffix = "\"}}";
        int overhead = prefix.getBytes(StandardCharsets.UTF_8).length + suffix.getBytes(StandardCharsets.UTF_8).length;
        int pad = HostContextUplink.MAX_UTF8_BYTES - overhead + 1;
        StringBuilder body = new StringBuilder(pad);
        for (int i = 0; i < pad; i++) {
            body.append('a');
        }
        collect(seen, HostContextUplink.evaluate(prefix + body + suffix));
        assertEquals(new TreeSet<>(RejectReason.allHostScopeRejectCodes()), seen);
    }

    private static void collect(TreeSet<String> seen, HostContextUplink.Decision d) {
        if (d.rejectReason != null) {
            seen.add(d.rejectReason.code());
        }
    }
}
