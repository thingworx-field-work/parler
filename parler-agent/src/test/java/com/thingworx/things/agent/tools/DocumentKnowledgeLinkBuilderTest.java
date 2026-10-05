package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Link builder parity with table-export helpers ({@code parler-ui/parler-ui.js}).
 */
class DocumentKnowledgeLinkBuilderTest {

    private static final String REPO = "AIDocRepository";
    private static final String NORMAL_PATH =
            "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf";

    @Test
    void path_style_link_with_page() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, NORMAL_PATH, 25);
        assertEquals(
                "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25",
                href);
    }

    @Test
    void path_without_leading_slash_is_normalized() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO,
                "document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf", 25);
        assertEquals(
                "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25",
                href);
    }

    @Test
    void path_with_spaces_is_encoded() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO,
                "/docs/my manual/file name.pdf", 3);
        assertEquals(
                "/Thingworx/FileRepositories/AIDocRepository/docs/my%20manual/file%20name.pdf#page=3",
                href);
    }

    @Test
    void path_with_non_ascii_is_utf8_encoded() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, "/docs/café/manual.pdf", 1);
        assertEquals(
                "/Thingworx/FileRepositories/AIDocRepository/docs/caf%C3%A9/manual.pdf#page=1",
                href);
    }

    @Test
    void path_with_question_mark_uses_downloader_form() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, "/docs/report?rev=1.pdf", 4);
        assertFalse(href.contains("/Thingworx/FileRepositories/"));
        assertEquals(
                "/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=docs%2Freport%3Frev%3D1.pdf#page=4",
                href);
    }

    @Test
    void path_with_hash_uses_downloader_form_and_strips_leading_slash_from_download_path() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, "/docs/part#1.pdf", 2);
        assertEquals(
                "/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=docs%2Fpart%231.pdf#page=2",
                href);
    }

    @Test
    void downloader_form_encodes_space_as_plus() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, "/docs/my manual?rev=1.pdf", 5);
        assertEquals(
                "/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=docs%2Fmy+manual%3Frev%3D1.pdf#page=5",
                href);
    }

    @Test
    void invalid_page_omits_fragment() {
        String href = DocumentKnowledgeLinkBuilder.buildPdfHref(REPO, NORMAL_PATH, 0);
        assertEquals(
                "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
                href);
        assertFalse(href.contains("#page="));
    }
}
