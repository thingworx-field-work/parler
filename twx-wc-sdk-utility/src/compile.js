const fs = require('fs-extra');
const path = require('path');
const handlebars = require('handlebars');

const {validate} = require('./validate');
const {logger, logWithException} = require('./logger');
const args = require('./args');

class MubCompiler {

    constructor(name, type) {
        this.dependencies = {};
    }

    compile(excludedLibraries) {
        const defaultVersion = args.version || '^2.0.0';

        // Simpler name for resolving file names
        const fn = path.resolve;

        // Main folders
        const mubFolder = fn(__dirname, '..'); // compile.js is in a sub-folder
        const projectFolder = args.wd ? fn(args.wd) : fn(__dirname, '..'); // compile.js is in a sub-folder

        // Contains static data to be copied as-is
        const staticFolder = fn(mubFolder, 'input', 'static');

        // static files for the whole package
        const staticExtensionLevelFolder = fn(staticFolder, 'extensionLevel');

        // static files for initializing a UI widget folder
        const staticWidgetLevelFolder = fn(staticFolder, 'widgetLevel');

        // Contains template data to be instantiated
        const templateFolder = fn(mubFolder, 'input', 'templates');

        // template files for initializing extension level data
        const templatesExtensionLevelFolder = fn(templateFolder, 'extensionLevel');

        // template files for initializing a UI widget folder
        const templatesWidgetLevelFolder = fn(templateFolder, 'widgetLevel');

        // template files for initializing  build level data
        const buildLevelFolder = fn(templateFolder, 'buildLevel');

        // UI component code
        const inputUiFolder = fn(projectFolder, 'input', 'ui');

        const inputWidgetWrapperFolder = fn(inputUiFolder, 'widgetWrapper');

        // Output Folders
        const outFolder = fn(projectFolder, 'tmp');
        const outConfigFolder = fn(outFolder, 'configfiles');
        const outUiFolder = fn(outFolder, 'ui');
        const outWidgetWrapperFolder = fn(outUiFolder, 'widgetWrapper');

        // Files
        const inputWidget_json = fn(projectFolder, 'input', 'widgets.json');
        const widgetLevelMap_json = fn(templateFolder, 'widgetLevelMap.json');

        const templateWidgetName_config_js = fn(templatesWidgetLevelFolder, 'widgetName.config.js');
        const outWidgetWrapper_config_js = fn(outWidgetWrapperFolder, 'widgetWrapper.config.js');

        const templatePtcswidgetsDeps_js = fn(templatesExtensionLevelFolder, 'ptcswidgetsDeps.js');
        const outPtcswidgetsDeps_js = fn(outFolder, 'ptcswidgetsDeps.js');

        const srcPolymerExports_js = fn(staticExtensionLevelFolder, 'polymer-exports.js');
        const outPolymerExports_js = fn(outFolder, 'polymer-exports.js');

        const templatePackage_json = fn(templatesExtensionLevelFolder, 'package.json.template');
        const outPackage_json = fn(outFolder, 'package.json');

        const templateMetadata_xml = fn(templatesExtensionLevelFolder, `metadata${args.dev ? '.dev' : ''}.xml`);
        const outMetadata_xml = fn(outConfigFolder, 'metadata.xml');

        const templateConfig_json = fn(templatesExtensionLevelFolder, 'config.json');
        const outConfig_json = fn(outFolder, 'config.json');

        const buildTemplatePackage_json = fn(buildLevelFolder, 'package.json');
        const outConfigPackage_json = fn(outConfigFolder, 'package.json');

        // Widgets and libs location for TWX in general and for TWX extension
        const twxRootPath = '/Thingworx/Common/thingworx/widgets/';
        const extensionsRootPath = '/Thingworx/Common/extensions/';
        const extensionsLibsPath = '/Thingworx/extensions/';

        // webpack paths
        const widgetDependencies = [];

        // Load widgets.json file
        function loadWidgetsJson(fileName) {
            if (!fs.existsSync(fileName)) {
                const errMsg = `${fileName} does not exist`;
                throw logWithException(errMsg);
            }
            const prefix = 'import!';

            try {
                return JSON.parse(fs.readFileSync(fileName).toString(), (_key, value) => {
                    if (typeof value === 'string' && value.startsWith(prefix)) {
                        const widget = value.substring(prefix.length, value.indexOf('.'));
                        if (excludedLibraries.includes(widget)) {
                            return undefined;
                        }
                        return loadWidgetsJson(fn(path.dirname(fileName), 'widgets', value.substring(prefix.length)));
                    }
                    return value;
                });
            } catch (e) {
                throw logWithException(`Can't parse ${fileName}`, e.message, e.logged);
            }
        }

        // Recursively load widgets.json, starting from root file
        const properties = loadWidgetsJson(inputWidget_json);

        try {
            validate(properties);
        } catch (e) {
            throw logWithException('Validation exception', e.message);
        }

        // Very awkward way to resolve a folder name ...
        // Is this still used?
        const stylesFolder = fn(inputWidget_json, '..', 'styles');

        // Read widgetLevelTemplateMap
        const widgetLevelTemplateMap = JSON.parse(fs.readFileSync(widgetLevelMap_json, 'utf8'));

        // ... and adjust map format
        for (const key in widgetLevelTemplateMap) {
            if (!widgetLevelTemplateMap.hasOwnProperty(key)) {
                continue;
            }
            const prop = widgetLevelTemplateMap[key];
            if (typeof prop === 'string') {
                widgetLevelTemplateMap[key] = {path: fn(templateFolder, prop)};
            } else if (prop.path) {
                prop.path = fn(templateFolder, prop.path);
            }
        }

        // Instantiate file from template
        function instantiateTemplate(templateFile, outputFile, context) {
            const source = fs.readFileSync(templateFile, 'utf8');
            const template = handlebars.compile(source, {noEscape: true});
            const result = template(context);
            fs.writeFileSync(outputFile, result);
        }

        // Register package dependencies
        function processDependencies(htmlImports, outDependencies, outImports, webpackOnly) {
            htmlImports.forEach(htmlImport => {
                if (!htmlImport.version) {
                    htmlImport.version = defaultVersion;
                }
                if (typeof htmlImport.module === 'undefined') {
                    htmlImport.module = 'es';
                }

                if (webpackOnly) {
                    widgetDependencies.push({path: '\'./' + htmlImport.url + '\''});
                } else {
                    if (htmlImport.from) {
                        outDependencies[htmlImport.id] = htmlImport;
                    }
                    if (htmlImport.url) {
                        outImports[htmlImport.id] = {
                            src:      htmlImport.url,
                            isModule: (htmlImport.module === 'es')
                        };
                    }
                }
            });
        }


        // Populates style properties for the widget
        function populateStyleProperties(styleProperties, currentComponent) {
            const styleFlatDict = [];
            const defaultStyleFlat = {};

            if (Array.isArray(styleProperties)) {
                styleProperties.forEach(prop => {
                    styleFlatDict.push({id: prop.id});
                    defaultStyleFlat[prop.id] = prop.defaultValue;
                });
            }

            currentComponent.styleFlatDict = styleFlatDict;
            currentComponent.defaultStyleFlat = defaultStyleFlat;
        }


        // Create folder, if it doesn't already exist
        function createFolder(folder) {
            try {
                fs.accessSync(folder);
            } catch (e) {
                fs.mkdirSync(folder);
            }
        }

        // Create output folders, if they doesn't already exist
        createFolder(outFolder);
        createFolder(outConfigFolder);
        createFolder(outUiFolder);

        if (!args.extension) {
            createFolder(outWidgetWrapperFolder);
        }

        // Copy global static extension level files
        fs.copySync(staticExtensionLevelFolder, outFolder);

        if (!args.extension) {
            // Copy widget Wrapper files
            fs.copySync(inputWidgetWrapperFolder, outWidgetWrapperFolder);
        }

        // For comparing file timestamps
        function fileTimeStamp(fileName) {
            try {
                const stats = fs.statSync(fileName);
                return stats.mtimeMs;
            } catch (e) {
                return 0;
            }
        }

        // ???
        if (properties.htmlImports) {
            processDependencies(properties.htmlImports, this.dependencies, {});
        }

        // Determine root path
        properties.rootPath = args.extension ? `${extensionsRootPath}${properties.extensionName}/ui/` : twxRootPath;

        if (!args.extension) {
            instantiateTemplate(
                templateWidgetName_config_js,
                outWidgetWrapper_config_js,
                {
                    widgetName: '',
                    config:     JSON.stringify({libsPath: extensionsLibsPath})
                });
        }

        const cleanExcluded = (components) => {
            const uiFolders = fs.readdirSync(outUiFolder);
            const widgets = components.filter(c => c).map(c => c.elementName.toLowerCase().replace(/-/g, ''));

            uiFolders.forEach(widgetFolder => {
                if (widgetFolder.startsWith('ptcs') && !widgets.includes(widgetFolder)) {
                    const widgetPath = fn(outUiFolder, widgetFolder);
                    if (fs.existsSync(widgetPath)) {
                        fs.rmSync(widgetPath, {recursive: true});
                        logger.debug(`Removed ${widgetPath}...`);
                    }
                }
            });
        };

        cleanExcluded(properties.components); // Clean excluded widgets from 'tmp/ui'

        // For each component
        properties.components.forEach(currentComponent => {
            const elementName = currentComponent.elementName;
            currentComponent.widgetName = currentComponent.widgetName || elementName.toLowerCase().replace(/-/g, '');
            currentComponent.extensionName = properties.extensionName;
            currentComponent.rootPath = properties.rootPath;
            currentComponent.imports = {};

            processDependencies(currentComponent.htmlImports, this.dependencies, currentComponent.imports, !args.extension);

            const componentName = currentComponent.widgetName;
            const componentFolder = fn(outUiFolder, componentName);
            const componentSrcFolder = fn(inputUiFolder, componentName);
            const compStylesFolder = fn(stylesFolder, componentName);
            const compStyleDict = fn(compStylesFolder, 'style.dict.json');
            const compStyleProperties = fn(compStylesFolder, 'style.properties.json');
            const styleProperties = fs.existsSync(compStyleProperties) ? JSON.parse(fs.readFileSync(compStyleProperties, 'utf8')) : undefined;

            currentComponent.styleDict = fs.existsSync(compStyleDict) ? JSON.parse(fs.readFileSync(compStyleDict, 'utf8')) : [];
            populateStyleProperties(styleProperties, currentComponent);

            currentComponent.config = JSON.stringify(currentComponent);

            // Create an empty directory for this component?
            const dirExists = fs.existsSync(componentFolder);
            if (!dirExists) {
                logger.info(`Folder for ${componentName} wrapper does not yet exist. Creating: ${componentFolder}`);
                logger.info(componentFolder);
                fs.mkdirSync(componentFolder);

                // First copy generic data and after that override it with source data
                fs.copySync(staticWidgetLevelFolder, componentFolder);

                if (fs.existsSync(componentSrcFolder)) {
                    fs.copySync(componentSrcFolder, componentFolder);
                }
            }

            // Loop through template files and write new files based on the properties provided
            for (const key in widgetLevelTemplateMap) {
                if (widgetLevelTemplateMap.hasOwnProperty(key)) {
                    const newFileName = fn(componentFolder, `${componentName}.${key}`);
                    const orgFileName = fn(componentSrcFolder, `${componentName}.${key}`);
                    const orgFileExist = fs.existsSync(orgFileName);
                    if (!orgFileExist && widgetLevelTemplateMap[key].excludeTemplate) {
                        continue;
                    }
                    if (!fs.existsSync(newFileName) || widgetLevelTemplateMap[key].overwrite) {
                        logger.debug(`Creating ${newFileName}...`);
                        if (orgFileExist) {
                            fs.copySync(orgFileName, newFileName);
                        } else {
                            instantiateTemplate(widgetLevelTemplateMap[key].path, newFileName, currentComponent);
                        }
                    } else if (fileTimeStamp(orgFileName) > fileTimeStamp(newFileName)) {
                        logger.debug(`Updating ${newFileName}...`);
                        fs.unlinkSync(newFileName);
                        fs.copySync(orgFileName, newFileName);
                    }
                }
            }
        });

        fs.copySync(srcPolymerExports_js, outPolymerExports_js);

        instantiateTemplate(templatePtcswidgetsDeps_js, outPtcswidgetsDeps_js, {widgetDependencies});

        // Extract dependencies for specific package manager
        this.depArray = function(from) {
            return Object.keys(this.dependencies).reduce((acc, key) => {
                if (this.dependencies[key].from === from) {
                    acc.push(this.dependencies[key]);
                }
                return acc;
            }, []);
        };

        instantiateTemplate(templatePackage_json, outPackage_json, {htmlImports: this.depArray('npm')});

        properties.dependencies = this.depArray('npm');
        properties.dependsOnExternalLibs = (properties.dependencies && properties.dependencies.length > 0);

        // Write configfiles / metadata.xml
        if (args.extension) {
            instantiateTemplate(templateMetadata_xml, outMetadata_xml, properties);
        } else if (fs.existsSync(outMetadata_xml)) {
            fs.unlinkSync(outMetadata_xml);
        }

        // Write config.jsons
        instantiateTemplate(templateConfig_json, outConfig_json, properties);

        // Write build-level package.json
        if (!args.extension) {
            instantiateTemplate(
                buildTemplatePackage_json,
                outConfigPackage_json,
                {
                    version: args.pr ? fs.readFileSync(path.resolve(`${projectFolder}/..`, 'version.txt'), 'utf8').trim() + args.pr
                        : require(`${projectFolder}/../devops/general_utils.cjs`).getVersionNumber(undefined, {minor: '0'})
                });
        } else {
            if (fs.existsSync(outConfigPackage_json)) {
                fs.unlinkSync(outConfigPackage_json);
            }

            if (fs.existsSync(fn(projectFolder, 'input', '.npmrc'))) {
                fs.copySync(fn(projectFolder, 'input', '.npmrc'), fn(outFolder, '.npmrc'));
            }
        }
    }

}

module.exports = MubCompiler;
