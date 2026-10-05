package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

class RepositoryTextLoadsTest {

    @Test
    void null_reader_is_missing() {
        RepositoryTextLoads.Result r = RepositoryTextLoads.loadText(null, "/x");
        assertEquals(RepositoryTextLoads.Kind.MISSING, r.kind());
    }

    @Test
    void reader_null_text_is_missing() throws Exception {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        RepositoryTextLoads.Result r = RepositoryTextLoads.loadText(reader, "/p");
        assertEquals(RepositoryTextLoads.Kind.MISSING, r.kind());
    }

    @Test
    void oversized_markdown_returns_null_from_loader() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < TypeTaxonomyMarkdownLoader.MAX_CHARS + 10; i++) {
            big.append('a');
        }
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return big.toString();
            }
        };
        assertNull(TypeTaxonomyMarkdownLoader.loadOrNullIfOversized(reader, null));
    }

    @Test
    void not_found_exception_classified_missing() {
        assertTrue(RepositoryTextLoads.isProbablyMissingFile(new java.io.FileNotFoundException("path not found")));
    }
}
