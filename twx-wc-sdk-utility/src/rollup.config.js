const terser = require('@rollup/plugin-terser');
const nodeResolve = require('@rollup/plugin-node-resolve');
const path = require('path');

const tmpFolder = path.resolve(__dirname, '..', 'tmp');
const targetFolder = path.resolve(__dirname, '..', 'dist', 'target');

module.exports.inputOptions = function(devmode) {
    return {
        input: {
            polymer:     path.resolve(tmpFolder, 'build/wc/polymer-exports.js'),
            lit3:        path.resolve(tmpFolder, 'build/wc/lit/development/index.all.js'),
            d3:          path.resolve(tmpFolder, 'build/wc/d3/src/index.js'),
            ptcswidgets: path.resolve(tmpFolder, 'build/wc/ptcswidgetsDeps.js')
        },
        plugins: [
            nodeResolve({
                moduleDirectories: ['wc']
            }),
            (!devmode && terser())
        ]
    };
};

module.exports.outputOptions = function(devmode) {
    return {
        dir:            path.resolve(targetFolder, 'webapp/common/lib'),
        format:         'esm',
        entryFileNames: '[name].bundle.js',
        sourcemap:      devmode
    };
};
