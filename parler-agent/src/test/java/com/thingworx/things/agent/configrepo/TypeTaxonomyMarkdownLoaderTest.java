package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.InfoTable;

class TypeTaxonomyMarkdownLoaderTest {

    @Test
    void missing_file_status() {
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
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.MISSING, o.status());
        assertEquals(0, o.measuredCharCount());
    }

    @Test
    void loaded_status_and_char_count() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return " # Title\n\nbody ";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.LOADED, o.status());
        assertEquals(" # Title\n\nbody ", o.promptText());
        assertEquals(o.promptText().length(), o.measuredCharCount());
    }

    @Test
    void loaded_preserves_leading_whitespace() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "  # Heading\n";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.LOADED, o.status());
        assertTrue(o.promptText().startsWith("  "));
        assertEquals("  # Heading\n", o.promptText());
        assertEquals(o.promptText().length(), o.measuredCharCount());
    }

    @Test
    void oversized_status_reports_raw_length() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < TypeTaxonomyMarkdownLoader.MAX_CHARS + 5; i++) {
            big.append('b');
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
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.OVERSIZED, o.status());
        assertEquals(big.length(), o.measuredCharCount());
        assertNull(TypeTaxonomyMarkdownLoader.loadOrNullIfOversized(reader, null));
    }

    @Test
    void bom_stripped_from_loaded_content() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "\uFEFF# Title\n\nbody";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.LOADED, o.status());
        assertEquals("# Title\n\nbody", o.promptText());
        assertFalse(o.promptText().startsWith("\uFEFF"));
        assertEquals(o.promptText().length(), o.measuredCharCount());
    }

    @Test
    void bom_only_yields_empty_status() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "\uFEFF";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.EMPTY, o.status());
        assertEquals("", o.promptText());
        assertEquals(0, o.measuredCharCount());
    }

    @Test
    void bom_plus_whitespace_yields_empty_status() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "\uFEFF   \n\t";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.EMPTY, o.status());
        assertEquals("", o.promptText());
        assertEquals(0, o.measuredCharCount());
    }

    @Test
    void blank_trimmed_file_is_empty_status() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return "   \n\t  ";
            }
        };
        TypeTaxonomyMarkdownLoader.TypeTaxonomyMarkdownOutcome o = TypeTaxonomyMarkdownLoader.loadWithStatus(reader, null);
        assertEquals(TypeTaxonomyMarkdownLoader.Status.EMPTY, o.status());
        assertEquals("", o.promptText());
        assertEquals(0, o.measuredCharCount());
    }
}
