# How to build

A Parler build produces two ThingWorx extension ZIPs, which you import together:

| Package | Source | Output |
| --- | --- | --- |
| Java agent extension | `parler-agent/` | `parler-agent/build/parler-agent.zip` (plus a versioned copy) |
| Chat widget extension | `parler-ui/` + `parler-ui-widget/` | `parler-ui-widget/dist/parler-ui-widget.zip` (plus a versioned copy) |

## Caution: ThingWorx build dependencies

The Java extension needs the ThingWorx Gradle plugins and platform libraries. The current Gradle configuration
resolves them from `https://artifactory.rd2.thingworx.io/artifactory`, which only a limited internal group can reach.
This will be addressed in the future.

Until then, if you cannot reach that Artifactory, you need a complete set of these JAR files in
`parler-agent/twx-lib/all/`. Someone with access produces that set with the `syncTwxLib` task (see
[`parler-agent/README.md`](./parler-agent/README.md)); it contains the ThingWorx Gradle plugins as well as the
compile, runtime and test libraries. A partial set does not work.

The widget build has no such restriction: it uses public npm packages only.

## Prerequisites

- **Git**, to clone this repository.
- **Node.js and npm**: Node **20 LTS** or **22 LTS** (the dependencies need at least Node 18).
- **gulp-cli**, installed globally. The widget packager (`twx-wc-sdk-utility/bin/cli.js`) runs the `gulp` command.
- **JDK 21**, the Java toolchain of the `parler-agent` Gradle build, with `java` on `PATH`.

Gradle does not need to be installed. `parler-agent/gradlew` (`gradlew.bat` on Windows) downloads Gradle **8.10**
when it first runs.

```bash
npm install -g gulp-cli
java -version
```

## One-time setup

The commands below are for macOS / Linux (bash). On Windows (PowerShell), use `\` in paths, `.bat` instead of `.sh`,
and the PowerShell lines shown where they differ.

### 1. Install npm dependencies

From the repository root:

```bash
(cd parler-ui && npm ci)
(cd parler-ui-widget && npm ci)
(cd twx-wc-sdk-utility && npm ci)
```

All three folders need their own install; the widget packaging step fails if `twx-wc-sdk-utility` is skipped.

### 2. Prepare the Java build

Choose one source for the ThingWorx dependencies.

**Option A: local libraries (`twx-lib`)**

1. Copy the complete JAR set into `parler-agent/twx-lib/all/`. The folder is ignored by Git; never commit it.
2. Delete any `android-json*.jar` from that folder. It provides its own `org.json` classes, which shadow the
   standard `json-*.jar` and cause `JSONException` compile errors.

   ```bash
   rm -f parler-agent/twx-lib/all/android-json*.jar
   ```

   ```powershell
   Remove-Item ".\parler-agent\twx-lib\all\android-json*.jar" -Force
   ```

3. Define placeholder values for `twxRepoUsername` and `twxRepoPassword`. The ThingWorx Gradle plugins require both
   properties to exist even when every dependency comes from `twx-lib/all`; any value works.

   ```bash
   export twxRepoUsername=local-offline
   export twxRepoPassword=local-offline
   ```

   ```powershell
   $env:twxRepoUsername = "local-offline"
   $env:twxRepoPassword = "local-offline"
   ```

**Option B: network (ThingWorx Artifactory)**

Leave `parler-agent/twx-lib/all/` empty and provide a real read-only resolver identity as `twxRepoUsername` /
`twxRepoPassword`, in `~/.gradle/gradle.properties` or as environment variables (`TWX_REPO_USERNAME` /
`TWX_REPO_PASSWORD` also work). Never commit credentials to `parler-agent/gradle.properties`.

## Build

From the repository root:

```bash
./build-extension.sh
./build-widget.sh
```

```powershell
.\build-extension.bat
.\build-widget.bat
```

- `build-extension` runs Gradle `assemble` in `parler-agent/`. If `parler-agent/twx-lib/all/` holds **more than ten**
  JARs it builds from those local libraries (`-PuseLocalTwxLib=true`); otherwise it resolves from the network
  (`-PuseLocalTwxLib=false`).
- `build-widget` builds the `parler-ui` bundle, syncs it into `parler-ui-widget`, and packages the Composer extension.

The outputs are listed at the top of this page.

### Building the widget step by step

`build-widget` runs these steps, which you can also run yourself:

```bash
cd parler-ui
npm run build:tw
cd ../parler-ui-widget
npm run sync
node ../twx-wc-sdk-utility/bin/cli.js
```

Details: [`parler-ui/README.md`](./parler-ui/README.md) and [`parler-ui-widget/README.md`](./parler-ui-widget/README.md).

## Run the tests

```bash
(cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true)   # drop the flag for a network build
(cd parler-ui && npm test)
(cd parler-ui-widget && npm test)
```

When you run `./gradlew` yourself, pass `-PuseLocalTwxLib=true` for a `twx-lib` build; the root scripts choose it
automatically, but direct Gradle calls do not.

## Troubleshooting

- **`Plugin with id 'com.thingworx.gradle.releasable' not found` after fixing `twx-lib/all`.** Gradle may have cached
  transformed copies of an earlier, bad JAR set. Delete the transforms cache and build again:

  ```bash
  rm -rf ~/.gradle/caches/8.10/transforms
  ./build-extension.sh
  ```

  ```powershell
  Remove-Item "$env:USERPROFILE\.gradle\caches\8.10\transforms" -Recurse -Force
  .\build-extension.bat
  ```

- **`twxRepoUsername not found and required for access to repository`.** The placeholder (Option A) or real (Option B)
  credentials are not defined in the shell or Gradle properties that run the build.
- **`Publishing project parler-agent to https://…` in the Gradle output.** The ThingWorx Gradle plugin prints this
  while configuring the project. `test` and `assemble` do not upload anything; the documented workflow never runs a
  `publish` task.

## Contributing

Before sending a change, build both packages and run the tests above. A change to a normative file under `CONTRACTS/`
also bumps `CONTRACTS/CONTRACT_VERSION.md` and adds a row to its changelog in the same commit.
