# Import Parler extensions and the sample mashup

## Goal

Install the two shipped **extensions**, then import the sample **mashup**:

1. **`parler-agent`** — Java ThingWorx extension (contains **`AIAgent`**, **`ParlerGateway`**, and agent implementation).
2. **`parler-ui-widget`** — widget bundle that hosts **`<parler-ui>`**.
3. **`ParlerAgentBasic.xml`** — sample mashup that uses the `parler-ui-widget`.

The extension and widget ZIPs are built from this repository (see *Build artifacts* below) or provided by your ThingWorx administrator or instructor. The sample mashup export is [`dev_data/ParlerAgentBasic.xml`](../../dev_data/ParlerAgentBasic.xml) in this repository.

This revision of the course targets **`parler-agent` 0.1.224** and **`parler-ui-widget` 0.1.92**. Import both
before importing or re-exporting the sample project; an older project export can omit mandatory Agent settings
or newer widget properties even when the chapter text is current.

Chapters [21](./21-agent-and-provider-configuration.md), [22](./22-prompt-to-response-and-chart.md) and [23](./23-supported-charts-and-tradeoffs.md) describe the newer **Agent 0.1.248 / Widget 0.1.97** pair identified by the book’s release label; the older course import assets above do not provide that newer reference baseline, so build or obtain the matching extensions before using those chapters as a configuration or feature reference.

## Build artifacts (maintainers only)

These paths are useful when you build the extensions from this repository. Most workshop students receive the finished ZIP/XML files instead.

- Agent ZIP: `parler-agent/build/parler-agent.zip` after **`./build-extension.sh`** (or equivalent Gradle assemble).
- Widget: follow **`./build-widget.sh`** and **`parler-ui-widget/README.md`** so Composer receives the current **`parler-ui`** bundle.

## Verification (screenshots)

After you import the two extensions, you should be able to find them on the `Installed Extensions` page.

Verify the installed package versions are at least the course target pair above. Feature-introduction versions
shown in later chapters are historical minimums and must not be mistaken for the current lab baseline.

<img src="./__images__//image-20260530015642854.png" alt="image-20260530015642854" style="zoom:50%;" />



Once you have imported the `ParlerAgentBasic.xml` file, you should be able to see the `Parler` mashup.

<img src="./__images__//image-20260530171358622.png" alt="image-20260530171358622" style="zoom:50%;" />
