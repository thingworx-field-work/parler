const args = require('yargs')
    .option('e', {
        alias:   'extension',
        type:    'boolean',
        default: false
    })
    .option('excludelibraries', {
        describe: 'specify names of libraries that should not be added to the libs folder',
        type:     'array'
    })
    .option('skip-pack', {
        describe: 'skip zip step',
        type:     'boolean',
        default:  false
    })
    .option('s', {
        alias:    'skip-install',
        describe: 'skip install step',
        type:     'boolean',
        default:  false
    })
    .option('lock', {
        describe: 'generate/use package-json.lock (--no-lock to disable)',
        type:     'boolean',
        default:  true
    })
    .option('wd', {
        describe: 'working directory path where input/widgets.json is located',
        type:     'string'
    })
    .option('pr', {
        describe: 'create a package for a PR',
        type:     'string'
    })
    .boolean('dev')
    .boolean('debug')
    .argv;

module.exports = args;
