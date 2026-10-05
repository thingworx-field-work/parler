/**
 * Rewrite FileRepository PDF links to the ThingWorx downloader endpoint so the
 * browser can render the PDF inline and honor `#page=N` fragments.
 *
 * @param {string} href
 * @param {string} origin
 * @returns {string}
 */
export function rewriteThingworxPdfHrefForInlineRender(href, origin) {
  const rawHref = String(href ?? "");
  const rawOrigin = String(origin ?? "").trim();
  if (!rawHref || !rawOrigin) return rawHref;

  try {
    const url = new URL(rawHref, rawOrigin);
    if (url.origin !== new URL(rawOrigin).origin) return rawHref;

    const fromFileRepositories = fileRepositoriesParts(url);
    if (fromFileRepositories && isPdfPath(fromFileRepositories.path)) {
      return downloaderHref(
        rawOrigin,
        fromFileRepositories.repository,
        fromFileRepositories.path,
        url.hash
      );
    }

    const fromDownloader = fileRepositoryDownloaderParts(url);
    if (fromDownloader && isPdfPath(fromDownloader.path)) {
      return downloaderHref(
        rawOrigin,
        fromDownloader.repository,
        fromDownloader.path,
        url.hash
      );
    }
  } catch {
    return rawHref;
  }

  return rawHref;
}

/** @param {URL} url */
function fileRepositoriesParts(url) {
  const prefix = "/Thingworx/FileRepositories/";
  if (!url.pathname.startsWith(prefix)) return null;
  const rest = url.pathname.slice(prefix.length);
  const slash = rest.indexOf("/");
  if (slash <= 0 || slash >= rest.length - 1) return null;

  try {
    return {
      repository: decodeURIComponent(rest.slice(0, slash)),
      path: `/${decodeURIComponent(rest.slice(slash + 1))}`,
    };
  } catch {
    return null;
  }
}

/** @param {URL} url */
function fileRepositoryDownloaderParts(url) {
  if (url.pathname !== "/Thingworx/FileRepositoryDownloader") return null;
  const repository = url.searchParams.get("download-repository") ?? "";
  const path = url.searchParams.get("download-path") ?? "";
  if (!repository.trim() || !path.trim()) return null;
  return { repository, path };
}

/** @param {string} path */
function isPdfPath(path) {
  const clean = String(path ?? "").trim();
  return /\.pdf$/i.test(clean);
}

/**
 * @param {string} origin
 * @param {string} repository
 * @param {string} path
 * @param {string} hash
 */
function downloaderHref(origin, repository, path, hash) {
  const repo = String(repository ?? "").trim();
  let rel = String(path ?? "").trim().replace(/\\/g, "/");
  if (!repo || !rel) return "";
  if (rel.startsWith("/")) rel = rel.slice(1);

  const q = new URLSearchParams();
  q.set("download-repository", repo);
  q.set("download-path", rel);
  q.set("directRender", "true");
  return `${origin}/Thingworx/FileRepositoryDownloader?${q.toString()}${hash || ""}`;
}
