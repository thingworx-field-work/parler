# Parler training course

An **mdBook** course for building on Parler with the **SCPA utilization** sample application: architecture,
taxonomy, hierarchy, built-in tools, skills from built-ins, extended tools, skills, Playbooks, embedding the widget,
and configuration.

The configuration, data-flow and chart reference covers **Agent 0.1.248 / Widget 0.1.97** in chapters 21–23; earlier
workshop assets keep the baseline they were written for.

## Layout

| Path | Contents |
| --- | --- |
| `src/` | Book chapters and appendices (`src/SUMMARY.md` is the table of contents) |
| `slides/` | Slide outlines for the four workshop days, and a preflight presentation |
| `workshop/day1` … `workshop/day4` | Staged configuration-repository content for each day (taxonomies, policies, skills, Playbooks, extended tools) and the Day 4 eval pack |
| `dev_data/` | ThingWorx exports used by the course; import them with `uv run import-dev --apply --import_control import_training` from the repository root |

The final, complete sample application is [`../dev_data/scpa_utilization`](../dev_data/scpa_utilization). The course
stages intentionally differ from it where a day teaches an intermediate step.

## Installing mdBook

**mdBook** is a standalone CLI (not shipped inside this repository). You only need the `mdbook` binary on your **`PATH`**.

### Pre-built binary (recommended)

1. Open **[mdBook Releases](https://github.com/rust-lang/mdBook/releases)**.
2. Download the archive for your OS and CPU, for example:
   - **Windows (64-bit):** `mdbook-v*-x86_64-pc-windows-msvc.zip`
   - **macOS Apple Silicon:** `mdbook-v*-aarch64-apple-darwin.tar.gz`
   - **macOS Intel:** `mdbook-v*-x86_64-apple-darwin.tar.gz`
   - **Linux:** `mdbook-v*-x86_64-unknown-linux-gnu.tar.gz` (or the `-musl` variant if you prefer musl)
3. Extract the archive. Add the directory that contains **`mdbook`** (on Windows, **`mdbook.exe`**) to your **`PATH`** (same idea on every OS: the shell must find the executable without typing a full path).

More detail: [mdBook — Installation — Pre-compiled binaries](https://rust-lang.github.io/mdBook/guide/installation.html#pre-compiled-binaries).

### macOS (Homebrew)

```bash
brew install mdbook
```

### Windows (package manager, optional)

If **winget** is available:

```bash
winget install --id Rustlang.mdBook -e
```

That installs a build published for winget and should place `mdbook` on `PATH` when the installer finishes. If your team uses **Scoop** or **Chocolatey**, search those catalogs for `mdbook`; otherwise use the **GitHub zip** above.

### Check

```bash
mdbook --version
```

## Optional: PDF export with mdbook-codi

`mdbook-codi` is installed with npm and provides the `mdbook-codi` executable used by mdBook's `[output.codi]` renderer. It primarily supports mdBook 0.5.x. The renderer is marked optional in `book.toml`: without it, `mdbook build` still produces the HTML book.

Install Node.js first if `npm` is not already available, then install the renderer globally:

```bash
npm install -g @xudesheng/mdbook-codi
mdbook-codi --version
```

This book can then be built normally:

```bash
mdbook build
```

With the `[output.codi]` configuration in `book.toml`, the PDF is written under `book/codi/`.

## Build

From `training/`:

```bash
mdbook build
mdbook serve --open

# or, to auto-reload on changes:

mdbook watch --open
```

Output: **`training/book/`** (gitignored).

## Installing mdbook support for mermaid

### Windows (package manager, optional)

If **winget** is available:

```bash
winget install badboy.mdbook-mermaid
```

### Check

```bash
mdbook-mermaid --version
```

### Adding mermaid to book

```bash
mdbook-mermaid install path/to/your/book
```

This will add the following configuration to the book.toml file:

```
[preprocessor.mermaid]
command = "mdbook-mermaid"

[output.html]
additional-js = ["mermaid.min.js", "mermaid-init.js"]
```
and also add the two javascript files to the book's root directory.

You can now run the `mdbook` commands above to build and serve the book with mermaid support.

### References
For additional information, refer to [mdbook-mermaid](https://github.com/badboy/mdbook-mermaid).
