## Wrapping in Package Mode

As ptcs widgets developer you may want to produce a ptcs-widgets package. To do that, you use the `mub` wrapper utility from _twx-visual-sdk/mub_ folder.

## Install the CLI

```bash
cd mub
npm install
```

## Use the CLI

Run from _twx-visual-sdk/mub_:
```bash
gulp
```

The builder now creates a TWX artifact that can be used in Thingworx Mashup Builder.

The build command can be modified with the following parameters.

|Option|Description|
|------|-----------|
|`--dev`|Create a non minimized Rollup bundle|
|`--debug`|Set the logger level to _debug_|
|`-s, --skip-install`|Don't invoke the install step (the install step is only needed the first time)|
|`--lock`|Generate package-json.lock (don't use `--no-lock` mode)'|
|`--excludelibraries`|Specify names of libraries that should be excluded (see `mub/input/static/ootbLibs.json` to check what libraries are already included/excluded from the ptcs-widgets package). You should specify the excluded folder names as they appear in `node_modules`. Example: ```gulp --excludelibraries @polymer,mocha,@webcomponents```|
|`clean`|Don't create an artifact. Instead remove the latest artifact and all temporary code used to build it. _Note_: if you perform this step you will need to re-link all components (by executing `npm run link-all` from the root folder) if the generated artifact should use the local code.|

### Command Output
The created artifact is delivered to the `dist/target` folder.

### Command Input
You work in _twx-visual-sdk/mub_. Your working folder should contain the following widget files structure.

- `input/widgets.json` specifies the components/widgets you want to include
- `input/widgets/` specifies properties for the resulting widgets
- `input/ui/` specifies Thingworx specific wrapping code that adapts the web components to Thingworx widgets. All components inherits from the `widgetWrapper` code (which is also in this folder).
- `input/styles` theming of the widget

### Logs
You can investigate the utility logs under _log_ folder in your working directory. Two kinds of logs are emitted:
- _combined.log_ has all the log messages
- _error.log_ has the log messages at error level

### Rollup Module Bundling

The gulp command produces a package which contains all ptcs web components and polymer libraries. The produced package doesn't contain separate web component and polymer files. All web components are bundled together using Rollup to one bundle and polymer libraries are bundled together to another bundle.

### Background Information and Future Plans

How this works:
- We have a configuration file for rollup in _mub/src_: __rollup.config.js__ that is stored in Git. Its content is specific to ptcs-widgets package.
- The _'gulp'_ command dynamically produces a __ptcswidgetsDeps.js__ which contains all web component imports that we need to bundle. This file is not stored in Git.
- Gulp task eventually produces 2 resulting bundles: __ptcswidgets.bundle.js__ and __polymer.bundle.js__. Those bundles have source maps so we can debug original files.

Next steps:
- Bundles should be loaded dynamically using WidgetWrapper and not statically in MB
- Bundles have big size. We should consider splitting it to smaller bundles and use lazy loading.
- Rollup gulp task doesn't output errors if exist
- Custom widgets extension using rollup
  - Should provide an option to bundle custom extension
  - Widget wrapper should be able to load custom extension bundle

New Hassle:

After rollup is introduced we will not be able to debug IDE (only Runtime). To be able to debug you can build ptcs-widgets package with '--dev' flag for the 'gulp command'.

### Further Reading
For more details, please see [WRAPPING PROCESS](COMMON.md).