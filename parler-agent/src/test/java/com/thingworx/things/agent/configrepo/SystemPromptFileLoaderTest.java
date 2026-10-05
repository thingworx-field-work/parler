package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.LeadingStablePromptComposer;
import com.thingworx.things.agent.PromptContextCacheSnapshot;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

class SystemPromptFileLoaderTest {

    private static InfoTable listing(String... fileNames) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String name : fileNames) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(name));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("F"));
            it.addRow(vc);
        }
        return it;
    }

    @Test
    void noMarkdownFiles_returnsDefault() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                assertEquals(ConfigurationRepositoryPaths.SYSTEM_PROMPT_ROOT, path);
                return listing();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertEquals(ExternalSystemPromptSelection.Mode.DEFAULT, sel.mode());
        assertFalse(sel.isActiveExternal());
    }

    @Test
    void singleMarkdownFile_caseInsensitiveExtension_loadsWholeBlock() {
        Map<String, String> texts = new HashMap<>();
        texts.put("/SystemPrompt/candidate.MD", "# External prompt\n\nBody.");
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return listing("candidate.MD", "notes.txt", "subdir");
            }

            @Override
            public String loadText(String path) {
                return texts.get(path);
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertTrue(sel.isActiveExternal());
        assertEquals("/SystemPrompt/candidate.MD", sel.activePath());
        assertEquals("# External prompt\n\nBody.", sel.promptText());
    }

    @Test
    void multipleMarkdownFiles_fallsBackWithDiagnostic() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return listing("a.md", "b.md");
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertEquals(ExternalSystemPromptSelection.Mode.DEFAULT, sel.mode());
        assertTrue(sel.fallbackDiagnostic().contains("multiple_files"));
    }

    @Test
    void emptyMarkdownFile_fallsBackWithDiagnostic() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return listing("empty.md");
            }

            @Override
            public String loadText(String path) {
                return "   \n";
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertFalse(sel.isActiveExternal());
        assertTrue(sel.fallbackDiagnostic().contains("empty_file"));
    }

    @Test
    void readFailure_fallsBackWithDiagnostic() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return listing("broken.md");
            }

            @Override
            public String loadText(String path) {
                throw new RuntimeException("disk");
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertFalse(sel.isActiveExternal());
        assertTrue(sel.fallbackDiagnostic().contains("read_error"));
    }

    @Test
    void listFailure_fallsBackWithDiagnostic() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                throw new RuntimeException("browse");
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertFalse(sel.isActiveExternal());
        assertTrue(sel.fallbackDiagnostic().contains("list_failed"));
    }

    @Test
    void directoryTypedD_withMdSuffix_isIgnored() {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        ValueCollection dir = new ValueCollection();
        dir.put(CommonPropertyNames.PROP_NAME, new StringPrimitive("archive.md"));
        dir.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
        it.addRow(dir);

        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return it;
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertEquals(ExternalSystemPromptSelection.Mode.DEFAULT, sel.mode());
        assertFalse(sel.isActiveExternal());
    }

    @Test
    void listedCandidateMissingAfterBrowse_fallsBackWithPathDiagnostic() {
        RepositoryReader reader = new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return listing("vanished.md");
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
        ExternalSystemPromptSelection sel = SystemPromptFileLoader.load(reader, "repo", NOPLogger.NOP_LOGGER);
        assertFalse(sel.isActiveExternal());
        assertTrue(sel.fallbackDiagnostic().contains("missing_file: /SystemPrompt/vanished.md"));
    }
}
