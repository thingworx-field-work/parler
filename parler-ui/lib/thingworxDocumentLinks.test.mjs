import assert from "node:assert/strict";
import test from "node:test";
import { rewriteThingworxPdfHrefForInlineRender } from "./thingworxDocumentLinks.js";

const origin = "https://tw.example.com";

test("rewrites FileRepositories PDF page link to downloader direct render", () => {
  const out = rewriteThingworxPdfHrefForInlineRender(
    "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/rk-t-operating-manual-7318042/source/original.pdf#page=12",
    origin
  );

  assert.equal(
    out,
    "https://tw.example.com/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=document-knowledge%2Frk-t-operating-manual-7318042%2Fsource%2Foriginal.pdf&directRender=true#page=12"
  );
});

test("adds directRender to existing downloader PDF links", () => {
  const out = rewriteThingworxPdfHrefForInlineRender(
    "/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=document-knowledge/kbm-coupling-manual-7318042/source/original.pdf#page=7",
    origin
  );

  assert.equal(
    out,
    "https://tw.example.com/Thingworx/FileRepositoryDownloader?download-repository=AIDocRepository&download-path=document-knowledge%2Fkbm-coupling-manual-7318042%2Fsource%2Foriginal.pdf&directRender=true#page=7"
  );
});

test("leaves non-PDF repository downloads unchanged", () => {
  const href =
    "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/index.json";
  assert.equal(rewriteThingworxPdfHrefForInlineRender(href, origin), href);
});

test("leaves external links unchanged", () => {
  const href =
    "https://other.example.com/Thingworx/FileRepositories/AIDocRepository/document-knowledge/manual.pdf#page=1";
  assert.equal(rewriteThingworxPdfHrefForInlineRender(href, origin), href);
});
