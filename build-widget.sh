#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT/parler-ui"
npm run build:tw
cd "$ROOT/parler-ui-widget"
npm run sync
exec node "$ROOT/twx-wc-sdk-utility/bin/cli.js"
