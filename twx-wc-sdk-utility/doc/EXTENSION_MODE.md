## Wrapping in Extension mode
You're an external developer who develops custom widgets. You will be using the utility as a global CLI tool from any location.

### CLI

- If you have _twx-wc-sdk-utility-[VERSION].zip_
    - Extract zip content to _twx-wc-sdk-utility_ folder
    - ``` bash
      cd twx-wc-sdk-utility
      npm link
      ```
- If you have Artifactory url then install it with npm:
    ``` bash
    npm install --global twx-wc-sdk-utility --registry=<ARTIFACTORY_URL>
    ```

## Use the CLI

Run from your working folder:
```bash
mub
```

The builder now creates a TWX artifact that can be used in the Thingworx Mashupbuilder.

The build command can be modified with the following parameters.

|Option|Description|
|------|-----------|
|`--debug`|Set the logger level to _debug_|
|`--skip-pack`|Don't create the zip artifact|
|`-s, --skip-install`|Don't invoke the install step (the install step is only needed the first time)|
|`--lock`|Generate package-json.lock (don't use `--no-lock` mode)'|
|`--excludelibraries`|Specify names of libraries that should be excluded (see `mub/input/static/ootbLibs.json` to check what libraries are already included/excluded from the ptcs-widgets package). You should specify the excluded folder names as they appear in `node_modules`. Example: ```gulp --excludelibraries @polymer,mocha,@webcomponents```|
|`clean`|Don't create an artifact. Instead remove the latest artifact and all temporary code used to build it.|

### Command output
The created artifact is delivered to the `dist` folder:

- `dist/target`: the unzipped _extension_
- `dist/{package name or extension name}.zip`: the zipped `target`


### Command input
You should create your working folder. It can be any folder.
Your working folder should contain the following widget files structure:

- `input/widgets.json` specifies the components/widgets you want to include
- `.npmrc` specifies Artifactory URL for your custom web components
- `input/widgets/` specifies properties for the resulting widgets
- `input/ui/` specifies Thingworx specific wrapping code that adapts the web components to Thingworx widgets. All components inherits from the `widgetWrapper` code (which is also in this folder).
- `input/styles` theming of the widget

### Logs
You can investigate the utility logs under _log_ folder in your working directory. 2 kinds of logs present:
- _combined.log_ has all the log messages
- _error.log_ has the messages with the error level

### Read more
For more details look at [WRAPPING PROCESS](COMMON.md)