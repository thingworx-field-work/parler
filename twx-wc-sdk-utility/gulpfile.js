const child_process = require('child_process');
const {src, dest, series, parallel, watch} = require('gulp');
const del = require('del');
const vinylPaths = require('vinyl-paths');
const fs = require('fs-extra');
const path = require('path');
const install = require('gulp-install');
const zip = require('gulp-zip');
const xmlEdit = require('gulp-edit-xml');
const gulpif = require('gulp-if');
const clean = require('gulp-clean');
const rename = require('gulp-rename');
const rollup = require('rollup');
const {logger, logWithException} = require('./src/logger');
const {validateComponentUrls} = require('./src/validate');
const MubCompiler = new require('./src/compile.js');
const rollupConfig = require('./src/rollup.config.js');
const rollupLit2Config = require('./src/rollup.lit2.config.js');
const replace = require('gulp-replace');
const { finished } = require('stream/promises');

const fn = path.resolve;
const args = require('./src/args');
const projectFolder = args.wd ? fn(args.wd) : __dirname;

const inputFolder = fn(projectFolder, 'tmp');
const buildFolder = fn(projectFolder, 'tmp', 'build');
const targetFolder = fn(projectFolder, 'dist', 'target');
const distFolder = fn(projectFolder, 'dist');
const libFolder = fn(targetFolder, 'webapp', 'common', args.extension ? '' : 'lib');
const wcFolder =  fn(buildFolder, 'wc');
const widgetsFolder = fn(targetFolder, 'ui');
const configfilesFolder = fn(inputFolder, 'configfiles');

// watch globs, relative to inputFolder path
let runWatchTask = false;
const wcSrcWatch = '../../src/**/*.js';
const UISrcWatch_js = '../input/ui/**/*.js';
const UISrcWatch_css = '../input/ui/**/*.css';
const widgetsSrcWatch = '../input/widgets/*.json';

let excludedLibs;
let includedLibs;

const wipComponents = !args.extension ? require('../devops/wip_components.json') : [];

const xmlOptions = {
    parserOptions:  {},
    builderOptions: {
        headless:   false,
        renderOpts: {
            pretty: true,
            indent: '    '
        }
    }
};

const mubCompiler = new MubCompiler();

let excludedLibraries = [];

if (args.extension && args.pr) {
    logWithException('PR cannot be used in extension mode');
}

if (args.excludelibraries) {
    // every lib can be several libs connected by comma
    excludedLibraries = args.excludelibraries.reduce((acc, lib) => acc.concat(lib.split(',')), excludedLibraries);
}

try {
    const ootbLibs = require('./input/static/ootbLibs.json');
    excludedLibs = ootbLibs.excluded;
    includedLibs = ootbLibs.included;
    excludedLibraries = excludedLibraries.concat(excludedLibs);

    if (!args.dev && !args.extension) {
        // Exclude work-in-progress components from bundle
        excludedLibraries = [...excludedLibraries, ...wipComponents];
    }

    if (args.extension) {
        // Ignore OOTB libraries
        excludedLibraries = excludedLibraries.concat(includedLibs);
    }
} catch (err) {
    logger.debug('Failed to load one of the following files: ootbLibs.json, wip_components.js');
}

// filter duplicates
excludedLibraries = excludedLibraries.filter((val, index, arr) => arr.indexOf(val) === index);


// Create folder, if it doesn't already exist
function createFolder(folder) {
    try {
        fs.accessSync(folder);
    } catch (e) {
        fs.mkdirSync(folder);
    }
}

// Create the inputFolder (= the tmp folder)
createFolder(inputFolder);

// Move to it - and perform the rest of the task from there
process.chdir(inputFolder);

// Compile sources
function task_compile(done) {
    mubCompiler.compile(excludedLibraries);
    done();
}

/*
 * Returns an array of glob paths to the libraries which should be included in the package/extension
 */
const getLibs = () => {
    const srcRegex = ['node_modules/**/*.*', 'node_modules/**/{LICENSE,LICENCE,COPYING,NOTICE}'];

    if (!args.extension) {
        srcRegex.push('polymer-exports.js');
        srcRegex.push('ptcswidgetsDeps.js');
    }

    let ignore = [
        'node_modules/**/demo/**',
        'node_modules/**/test/**',
        'node_modules/**/tests/**',
        'node_modules/**/node_modules/**',
        'node_modules/**/vaadin-grid/grid.gif'
    ];

    // excluded libraries
    excludedLibraries.forEach(function(lib) {
        ignore.push('node_modules/**/' + lib + '/**');
    });

    // let prefix = process.cwd() + '/';
    const prefix = '!';
    ignore = ignore.map(pattern => prefix + pattern);

    return srcRegex.concat(ignore);
};

const getImportRegex = (lib) => `import(.*)('|")(${lib}.*)('|")[;]{0,1}`;

/*
 * Get a regex of all polymer imports which are already included in TWX
 */
const getPolymerImportsToReplace = () => {
    let importsToReplace = '';
    includedLibs.forEach(function(lib) {
        if (lib.indexOf('@polymer') > -1) {
            importsToReplace += `|${getImportRegex(lib.replace('-', '\\-'))}`;
        }
    });
    if (importsToReplace) {
        importsToReplace = importsToReplace.substring(1); // remove the leading '|'
    }

    return importsToReplace;
};

/*
*  Link WIP components to mub/tmp for dev mode
*/
const linkWipComponents = () => {
    console.error('\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! linkWipComponents !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n');
    /*
    const {processPackages, linkPackage} = require('../devops/npm_links');
    const packages = processPackages();

    wipComponents.forEach(component => {
        const depFolder = fn(inputFolder, 'node_modules', component);
        const componentMeta = packages.find(item => item.name === component);
        if (componentMeta && componentMeta.folder) {
            linkPackage(depFolder, componentMeta.folder);
        }
    });
    */
};

// Copies node modules to the target folder
function task_buildDeps() {
    // * In case we are in extension mode we should replace some imports.
    // E.g. import { PTCS } from 'ptcs-library/library.js'; should be replaced by import { PTCS } from '../../Common/wc/lib/ptcswidgets.bundle.js'.
    const polymerImportsToReplace = getPolymerImportsToReplace();
    const ptcsImportsToReplace = `${getImportRegex('ptcs-')}|${getImportRegex('ptc1-')}`;
    const d3ImportsToReplace = getImportRegex('d3');
    const litImportsToReplace = getImportRegex('lit');

    function importReplacer(importString) {
        return function(match, p1, p2, p3) {
            const pathSplit = this.file.relative.split(/(\/|\\)/);
            const filename = path.basename(this.file.path);
            let relativePath = '';

            if (!filename.endsWith('.js')) {
                return match;
            }

            // Calculate the number of sublevels
            for (let i = 0; i < pathSplit.length; i++) {
                if (i !== 0 && i !== pathSplit.length - 1 && (pathSplit[i] === '/' || pathSplit[i] === '\\')) {
                    relativePath += '../';
                }
            }

            if (importString) {
                return match.replace(/('|").*('|")/, `'../${relativePath}Common/wc/lib/${importString}'`);
            }

            return match.replace(/('|")/, `$1${relativePath}`);
        };
    }

    return src(`${wcFolder}/*`)
        .pipe(vinylPaths(del))
        .pipe(src(getLibs(), {follow: true}))
        .pipe(gulpif(!args.extension && /^@webcomponents/, dest(libFolder)))
        .pipe(gulpif(!args.extension, dest(wcFolder)))
        .pipe(gulpif(args.extension, replace(new RegExp(ptcsImportsToReplace, 'g'), importReplacer('ptcswidgets.bundle.js'))))
        .pipe(gulpif(args.extension, replace(new RegExp(polymerImportsToReplace, 'g'), importReplacer('polymer.bundle.js'))))
        .pipe(gulpif(args.extension, replace(new RegExp(d3ImportsToReplace, 'g'), importReplacer('d3.bundle.js'))))
        .pipe(gulpif(args.extension, replace(new RegExp(litImportsToReplace, 'g'), importReplacer('lit.bundle.js'))))
        .pipe(gulpif(args.extension, replace(new RegExp(getImportRegex('[^\\.]'), 'g'), importReplacer(''))))
        .pipe(gulpif(args.extension, dest(libFolder)));
}

// Combine the subparts into a physical widgetWrapper.js
function task_createWidgetwrapper(done) {
    if (!args.extension) {
        rollup.rollup({input: 'ui/widgetWrapper/widgetWrapperTemplate.js'})
            .then((result) => {
                return result.write({output: {
                    file:   'ui/widgetWrapper/widgetWrapper.js',
                    format: 'iife'
                }});
            })
            .then(() => {
                done();
            })
            .catch((err) => {
                throw logWithException(err.message);
            });
    } else {
        done();
    }
}

// Transpiles widgets and copies them to the target folder
function task_buildWidgets() {
    return src(['ui/**/*.*', '!ui/widgetWrapper/widgetWrapperTemplate.js', '!ui/widgetWrapper/ww*.js'])
        .pipe(dest(widgetsFolder));
}

function task_buildMetadata() {
    if (args.extension) {
        return src(fn(configfilesFolder, 'metadata.xml'))
            .pipe(
                xmlEdit(function(xml) {
                    try {
                        const version = require('../../devops/general_utils.cjs').getVersionNumber();

                        if (version) {
                            xml.Entities.ExtensionPackages[0].ExtensionPackage[0].$.packageVersion = version;
                        }
                    } catch (err) {
                        // don't update version
                    }

                    return xml;
                }, xmlOptions)
            )
            .pipe(dest(targetFolder));
    }

    return src(fn(targetFolder, 'metadata.xml'), {read: false, allowEmpty: true})
        .pipe(clean());
}

function task_buildPackagejson() {
    if (!args.extension) {
        return src(fn(configfilesFolder, 'package.json'))
            .pipe(dest(targetFolder));
    }

    return src(fn(targetFolder, 'package.json'), {read: false, allowEmpty: true})
        .pipe(clean());
}

function task_install(done) {
    const depsLocation = fn(inputFolder, 'node_modules');

    if (!args.skipInstall) {
        const npmArgs = [];

        if (!args.dev && !args.debug) {
            npmArgs.push('--silent');
        }

        if (!args.lock) {
            npmArgs.push('--no-package-lock');
        }

        src([fn(inputFolder, 'package.json')])
            .pipe(install(Object.assign({
                production: true
            }, npmArgs.length === 0 ? null : {args: npmArgs}), () => {
                if (args.pr) {
                    child_process.execSync('npm run link-all -- --exclude-theme-engine', {cwd: fn(projectFolder, '..')});
                }
                if (args.dev) {
                    linkWipComponents();
                }
                validateComponentUrls(mubCompiler.dependencies, depsLocation);
                done();
            }));
    } else {
        if (!fs.pathExistsSync(depsLocation)) {
            logger.info('WRAPPER TOOL WARNING: You\'re skipping install step (--skip-install) but tmp/node_modules doesn\'t exist!');
        }

        logger.info('Skipping install');
        validateComponentUrls(mubCompiler.dependencies, depsLocation);
        done();
    }
}

function task_installLit2(done) {
    if (!args.skipInstall) {
        const npmArgs = [];

        if (!args.dev && !args.debug) {
            npmArgs.push('--silent');
        }

        if (!args.lock) {
            npmArgs.push('--no-package-lock');
        }

        src([fn(inputFolder, 'lit2', 'package.json')])
            .pipe(install(Object.assign({
                production: true
            }, npmArgs.length === 0 ? null : {args: npmArgs}), () => {
                done();
            }));
    }

    done();
}

function task_modifyPtcsMoment(done) {
    if (args.extension) {
        done();
    } else {
        const folder = fn(wcFolder, 'ptcs-moment');
        const momentImportFile = fn(folder, 'moment-import.js');
        return fs.rename(momentImportFile, fn(folder, 'moment-import.js.sdk'))
            .then(() => fs.rename(fn(folder, 'moment-import.js.mb'), momentImportFile));
    }

    return undefined;
}

function task_cleanDist(done) {
    logger.info('Starting wrapping process and cleaning the leftovers');

    // Must change back to start folder, or del.sync don't want to remove the folder (which is good)
    const cwd = process.cwd();
    process.chdir(projectFolder);
    try {
        del.sync([fn(distFolder, '*.zip'), fn(targetFolder, '*')]);
    } catch (err) {
        logger.error(err.message);
    }
    process.chdir(cwd);
    done();
}

function task_pack(done) {
    if (args.skipPack) {
        logger.info('Skipping pack');
        done();
        return undefined;
    }
    if (!args.e) {
        logger.debug('Pack is not needed in package mode');
        done();
        return undefined;
    }
    const cfg = require(fn(inputFolder, 'config.json'));
    const extName = cfg.extensionName || 'ext';
    const versionedZip = cfg.zipName || (extName + '.zip');
    const stableZip = `${extName}.zip`;

    const packStream = src([targetFolder + '/**/*.*', targetFolder + '/**/{LICENSE,LICENCE,COPYING,NOTICE}'])
        .pipe(zip(versionedZip))
        .pipe(dest(distFolder));

    return finished(packStream)
        .then(() => {
            const versionedPath = fn(distFolder, versionedZip);
            const stablePath = fn(distFolder, stableZip);
            if (versionedZip !== stableZip && fs.existsSync(versionedPath)) {
                fs.copyFileSync(versionedPath, stablePath);
                logger.info(`Also wrote stable artifact: ${stableZip}`);
            }
        })
        .catch((err) => {
            logger.error(err.message);
            throw err;
        });
}

function task_rollup(done) {
    if (!args.extension) {
        rollup.rollup(rollupConfig.inputOptions(args.dev || runWatchTask))
            .then((bundle) => {
                return bundle.write(rollupConfig.outputOptions(args.dev || runWatchTask));
            })
            .then(() => {
                done();
            })
            .catch((err) => {
                throw logWithException(err.message);
            });
    } else {
        logger.debug('Rollup phase is not performed when we create extension');
        done();
    }
}

function task_rollupLit2(done) {
    if (!args.extension) {
        rollup.rollup(rollupLit2Config.inputOptions(args.dev || runWatchTask))
            .then((bundle) => {
                return bundle.write(rollupLit2Config.outputOptions(args.dev || runWatchTask));
            })
            .then(() => {
                done();
            })
            .catch((err) => {
                throw logWithException(err.message);
            });
    } else {
        logger.debug('Rollup phase is not performed when we create extension');
        done();
    }
}

function task_clean() {
    // Must change back to start folder, or del.sync don't want to remove the folder (which is good)
    process.chdir(projectFolder);
    del.sync([`${distFolder}/**`, `${inputFolder}/**`]);
}

// Archive wc components for an external developer
function task_archiveWC() {
    if (args.e) {
        logger.info('This task is not available in the extension mode');
        return null;
    }

    const depReplacer = function(match, p1) {
        let cmptLocation = `file:../${p1}`;

        if ((p1 === '@webcomponents/webcomponentsjs')) {
            cmptLocation = 'file:../webcomponentsjs';
        }

        return `"${p1}": "${cmptLocation}"`;
    };

    const regex = '"(ptc[s,1].*|@webcomponents\\/webcomponentsjs)":\\s".*"';

    // Remove timestamp from ptcs-standalone-themes
    const packageJsonPath = fn(projectFolder, '../ptcs-standalone-themes/package/package.json');
    if (fs.existsSync(packageJsonPath)) {
        // Replace timestamp in version with "-0" instead
        const json = JSON.parse(fs.readFileSync(packageJsonPath, 'utf8'));
        json.version = json.version.replace(/-(.*)/, '-0');
        fs.writeFileSync(packageJsonPath, JSON.stringify(json, null, 2));
    }

    const srcRegex = [
        'src/*/ptcs-*/**/*.*',
        '!src/contribution/**/*',
        '!src/legacy/**/*',
        '!src/*/ptcs-*/{test/**/*,demo/**/*}',
        'mub/tmp/build/wc/{ptcs-base-theme,ptcs-base-theme/**}',
        '{ptcs-standalone-themes,ptcs-standalone-themes/**}',
        'mub/doc/EXTERNAL_DEV.md'
    ];

    const options = {
        cwd:    fn(projectFolder, '..'),
        follow: true
    };

    return src(srcRegex, options)
        .pipe(rename(srcPath => {
            if (!srcPath.dirname.includes('ptcs-standalone-themes') && !srcPath.dirname.includes('ptcs-base-theme')) {
                // Flatten behaviors|components|internal|utils parent directories prefix from path.
                srcPath.dirname = process.platform.startsWith('win') ? srcPath.dirname.replace(/[^\\]*/, '') : srcPath.dirname.replace(/[^/]*/, '');
            }
        }))
        .pipe(replace(new RegExp(regex, 'g'), depReplacer))
        .pipe(zip('twx-wc-sdk.zip'))
        .pipe(dest(projectFolder));
}

function task_endWithSuccess(done) {
    logger.info('Wrapping finished successfully');
    done();
}

function task_watchEnded(done) {
    logger.info('');
    logger.info('');
    logger.info('');
    logger.info('********************');
    logger.info(' Gulp watch updated');
    logger.info('********************');
    logger.info('');
    logger.info('');
    logger.info('');
    done();
}

exports.clean = task_clean;
exports.archiveWC = task_archiveWC;

exports.default = series(
    task_cleanDist,
    task_compile,
    task_install,
    task_installLit2,
    parallel(
        series(task_buildDeps, task_modifyPtcsMoment),
        series(task_createWidgetwrapper, task_buildWidgets),
        parallel(task_buildMetadata, task_buildPackagejson),
    ),
    task_rollup,
    task_rollupLit2,
    task_pack,
    task_endWithSuccess
);

exports.watch = () => {
    runWatchTask = true;
    watch([wcSrcWatch, UISrcWatch_js, UISrcWatch_css, widgetsSrcWatch], series(
        task_cleanDist,
        task_compile,
        parallel(
            series(task_buildDeps, task_modifyPtcsMoment),
            series(task_createWidgetwrapper, task_buildWidgets),
            task_buildPackagejson
        ),
        task_rollup,
        task_watchEnded
    ));
    logger.info('');
    logger.info('');
    logger.info('');
    logger.info('******************');
    logger.info(' Gulp watch ready');
    logger.info('******************');
};
