# Parler

**ThingWorx chat widget** — **`parler-ui`** (`<parler-ui>` Lit component) packaged as **`parler-ui-widget`** for Composer. Wire protocol and UI state: **`CONTRACTS/`** (start with **`CONTRACTS/API_CONTRACT.md`**, **`CONTRACTS/UI_CLIENT_PROTOCOL.md`**) and narrative **`docs/architecture/agent-alwayson.md`**.

## What is 'parler'

Please see [**`parler-introduction.md`**](./parler-introduction.md).

## Repo layout

| Path | Purpose |
|------|---------|
| **`parler-ui/`** | Lit component, AlwaysOn wiring, charts; **`npm run build:tw`** |
| **`parler-ui-widget/`** | Extension metadata + sync from `parler-ui`; **mub** packaging |
| **`parler-agent/`** | Java ThingWorx agent extension (Gradle); build and **offline `twx-lib`** flow: **`parler-agent/README.md`** |
| **`CONTRACTS/`** | Normative wire / UI contracts |
| **`docs/`** | Design and reference documentation (agent, UI, architecture, operations) |
| **`training/`** | Training course (mdBook) and staged workshop material for the SCPA utilization sample: **`training/README.md`** |
| **`dev_data/`** | Sample ThingWorx exports and configuration; **`dev_data/scpa_utilization/`** is the complete sample application |
| **`test_scripts/`** | Dev helpers: **`uv run reset-dev`**, **`uv run import-dev`**, **`uv run agent-eval`**, **`uv run parler-collect-live`** (see root **`pyproject.toml`**) |
| **`scripts/`** | Repository checks and the release-pair build script |
| **`twx-wc-sdk-utility/`** | Velotic Web Component SDK utility (**mub**) used by local widget builds |


## How to build

Please see [**`how-to-build.md`**](./how-to-build.md).




## Reporting issues

Issues and suggestions can be reported at [**Issues**](https://github.com/thingworx-field-work/parler/issues). They will be resolved if there is time available.


# Disclaimer
By downloading this software, the user acknowledges that it is unsupported, not reviewed for security purposes, and that the user assumes all risk for running it.

Users accept all risk whatsoever regarding the security of the code they download.

This software is not an official Velotic product and is not officially supported by Velotic.

Velotic is not responsible for any maintenance for this software.

Velotic will not accept technical support cases logged related to this Software.

This source code is offered freely and AS IS without any warranty.

The author of this code cannot be held accountable for the well-functioning of it.

The author shared the code that worked at a specific moment in time using specific versions of Velotic products at that time, without the intention to make the code compliant with past, current or future versions of those Velotic products.

The author has not committed to maintain this code and he may not be bound to maintain or fix it.


# License
I accept the MIT License (https://opensource.org/licenses/MIT) and agree that any software downloaded/utilized will be in compliance with that Agreement. However, despite anything to the contrary in the License Agreement, I agree as follows:

I acknowledge that I am not entitled to support assistance with respect to the software, and Velotic will have no obligation to maintain the software or provide bug fixes or security patches or new releases.

The software is provided “As Is” and with no warranty, indemnitees or guarantees whatsoever, and Velotic will have no liability whatsoever with respect to the software, including with respect to any intellectual property infringement claims or security incidents or data loss.
