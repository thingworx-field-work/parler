# parler-agent

ThingWorx Java extension (Agent) for Parler. Product behavior, services, and developer context are documented under **[`../docs/agent/`](../docs/agent/)** (start with **`AGENT-CONTEXT.md`**).

## Prerequisites

- **JDK 21** (Gradle uses a Java 21 toolchain for this project).
- **ThingWorx build dependencies**, from one of two sources:
  - **Network (ThingWorx Artifactory):** for developers with access to the ThingWorx Artifactory, the default
    `twxRepoUrl` in `gradle.properties` resolves the ThingWorx Gradle plugins and platform libraries, together with
    Maven Central. Set read-only resolver credentials **`twxRepoUsername`** / **`twxRepoPassword`** in
    `~/.gradle/gradle.properties` or the environment (aliases **`TWX_REPO_USERNAME`** / **`TWX_REPO_PASSWORD`**). Never
    commit credential values to `parler-agent/gradle.properties`.
  - **Local libraries (`twx-lib`):** everyone else provides the ThingWorx libraries from their own licensed ThingWorx
    environment in **`twx-lib/all/`** and builds with **`-PuseLocalTwxLib=true`**; see
    [Offline / air-gapped dependencies](#offline--air-gapped-dependencies-twx-lib).

The documented workflow uses Gradle for **`test`** and **`assemble`** only; it does not publish artifacts.

## Build (normal, network-backed)

Run Gradle from **this directory** (where `gradlew`, `build.gradle`, and `settings.gradle` live):

```bash
cd parler-agent
./gradlew assemble --no-daemon
```

On Windows:

```bat
cd parler-agent
gradlew.bat assemble --no-daemon
```

From the monorepo root you can use:

```bat
parler-agent\gradlew.bat -p parler-agent assemble --no-daemon
```

The extension ZIP is produced under **`build/`** (including the stable alias **`parler-agent.zip`** when the `extensionZip` task runs as part of `assemble`). The repo root **`build-extension.bat`** / **`build-extension.sh`** scripts also drive this Gradle build. Those scripts **auto-pick offline vs network** for that run: if **`twx-lib/all/`** exists and contains **more than ten** `*.jar` files, they pass **`-PuseLocalTwxLib=true`**; otherwise they pass **`-PuseLocalTwxLib=false`** (so a partial vendor tree does not accidentally enable offline mode). Calling **`./gradlew`** yourself uses only **`gradle.properties`** / **`-P`** / env as documented below.

## Offline / air-gapped dependencies (`twx-lib`)

By default the build resolves **ThingWorx Gradle plugins**, **Shadow**, and **compile / runtime / test** libraries from the network. You can **vendor** those JARs into **`twx-lib/all/`** and switch the build to **local jars only** (no Maven Central or Artifactory for dependency resolution).

### 1. Populate `twx-lib/all` (one-time or refresh)

On a machine that can reach Artifactory and Maven Central, with valid read-only resolver credentials:

```bat
cd parler-agent
gradlew.bat syncTwxLib --no-daemon
```

This runs the **`syncTwxLib`** task: it copies the resolved **buildscript** classpath plus **compile**, **runtime**, and **test** classpaths into **`twx-lib/all/`**.

If **`useLocalTwxLib=true`** is already set in `gradle.properties` (or you export **`PARLER_AGENT_USE_LOCAL_LIBS=1`**), Gradle would try to build only from `twx-lib/all` before it is filled. In that case **force network mode for this command only**:

```bat
gradlew.bat -PuseLocalTwxLib=false syncTwxLib --no-daemon
```

Copy the populated **`twx-lib/`** tree (at least **`twx-lib/all/*.jar`**) to your offline environment alongside the rest of **`parler-agent/`**.

### 2. Enable offline libs

Turn on **one** of these (they are equivalent in intent; the first is usually easiest):

| Mechanism | How |
|-----------|-----|
| **`gradle.properties`** | Set **`useLocalTwxLib=true`** (next to the existing comment in this repo’s `gradle.properties`). |
| **Command line** | **`gradlew.bat -PuseLocalTwxLib=true assemble`** (or any task). |
| **Environment** | Set **`PARLER_AGENT_USE_LOCAL_LIBS=1`**. |

With offline mode on, the build **requires at least one `*.jar` in `twx-lib/all/`**. If the folder is empty, Gradle fails with an explicit message telling you to run **`syncTwxLib`** with network mode first.

### 3. Git and large binaries

**`twx-lib/.gitignore`** ignores **`all/*.jar`** so vendored JARs are not committed by default. If your team needs jars in git (or Git LFS), adjust or remove those ignore rules locally.

## Implementation notes (contributors)

- **`settings.gradle`** computes a boolean **`gradle.ext.parlerUseLocalTwxLib`** from **`useLocalTwxLib`** / **`PARLER_AGENT_USE_LOCAL_LIBS`**. The top of **`build.gradle`** mirrors that as **`useLocalTwxLib`** for the main script.
- Inside **`buildscript { }`**, do **not** branch on the bare name **`useLocalTwxLib`**: there it can resolve to the **String** project property from `gradle.properties` (`"false"`), which is **truthy** in Groovy and would select the wrong branch. The build script uses **`offlineLibs`** derived from **`gradle.ext.parlerUseLocalTwxLib`** instead.
