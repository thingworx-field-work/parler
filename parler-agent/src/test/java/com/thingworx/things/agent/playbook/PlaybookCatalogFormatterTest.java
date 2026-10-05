package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookCatalogFormatterTest {

    private static PlaybookCatalogEntry entry(String id, String title, String whenToUse) {
        return new PlaybookCatalogEntry(id, title, "", whenToUse, "/playbooks/" + id + "/playbook.json",
                new JSONObject(), new JSONObject());
    }

    @Test
    void empty_returnsEmptyString() {
        assertEquals("", PlaybookCatalogFormatter.format(List.of()));
        assertEquals("", PlaybookCatalogFormatter.format(null));
    }

    @Test
    void sortsById_andOmitsBlankWhenToUse() {
        String md = PlaybookCatalogFormatter.format(List.of(
                entry("zebra", "Z Title", "When Z"),
                entry("alpha", "A Title", ""),
                entry("beta", "B Title", "When B")));
        assertTrue(md.indexOf("`alpha`") < md.indexOf("`beta`"));
        assertTrue(md.indexOf("`beta`") < md.indexOf("`zebra`"));
        assertTrue(md.contains("When: When B"));
        assertFalse(md.contains("alpha\n  - When:"));
        assertFalse(md.contains("description"));
    }
}
