const terser = require('@rollup/plugin-terser');
const nodeResolve = require('@rollup/plugin-node-resolve');
const path = require('path');

const tmpFolder = path.resolve(__dirname, '..', 'tmp');
const targetFolder = path.resolve(__dirname, '..', 'dist', 'target');

module.exports.inputOptions = function(devmode) {
    return {
        input: {
            lit: path.resolve(tmpFolder, 'lit2/node_modules/lit/development/index.all.js'),
        },
        plugins: [
            nodeResolve({
                moduleDirectories: ['node_modules']
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
