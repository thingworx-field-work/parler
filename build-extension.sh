#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/parler-agent"

# If twx-lib/all exists and has more than 10 vendored jars, prefer offline build for this invocation.
# Otherwise force network mode (-PuseLocalTwxLib=false) so a partial twx-lib tree does not break the build.
GRADLE_TWX_LOCAL=(-PuseLocalTwxLib=false)
if [[ -d twx-lib/all ]]; then
  shopt -s nullglob
  jars=(twx-lib/all/*.jar)
  shopt -u nullglob
  count=${#jars[@]}
  if ((count > 10)); then
    GRADLE_TWX_LOCAL=(-PuseLocalTwxLib=true)
    echo "build-extension.sh: twx-lib/all has ${count} jar(s); using -PuseLocalTwxLib=true"
  fi
fi

# For unit tests from parler-agent/: use the same offline flag, e.g.
#   ./gradlew test --no-daemon "${GRADLE_TWX_LOCAL[@]}"
# When twx-lib/all is sparse, GRADLE_TWX_LOCAL is (-PuseLocalTwxLib=false) so Gradle resolves plugins from Artifactory.

exec ./gradlew assemble --no-daemon "${GRADLE_TWX_LOCAL[@]}"
