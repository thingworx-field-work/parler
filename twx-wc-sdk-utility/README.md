# ThingWorx Web Component SDK Utility

## Overview
ThingWorx Web Component SDK Utility can do 2 things:
- Wrap ptcs widgets as ptcs-widgets package which is a built-in part of TWX Mashup Builder
- Wrap custom web component widgets as an extension that can be imported into TWX Mashup Builder

In general, it wraps each polymer component specified as a TWX Mashup Builder widget.

### Prerequisites
Install `gulp-cli` globally
```bash
npm install -g gulp-cli
```

### Working modes
There are 2 working modes:
- **Package mode**. If you are ptcs components developer go through the [package mode guide](doc/PACKAGE_MODE.md).
- **Extension mode**. If you are developing custom web components go through the [extension mode guide](doc/EXTENSION_MODE.md).