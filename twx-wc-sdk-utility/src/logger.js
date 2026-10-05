const path = require('path');
const args = require('./args');

const projectFolder = args.wd ? path.resolve(args.wd) : path.resolve(__dirname, '..');

const {createLogger, format, transports} = require('winston');
const {combine, timestamp, printf} = format;

const myFormat = printf(({level, message, timestamp: tmstmp}) => {
    return `${tmstmp} ${level}: ${message}`;
});

const consoleFormat = printf(({level, message}) => {
    return `${level}: ${message}`;
});

const debugMode = args.debug || args.dev;

const logLevel = debugMode ? 'debug' : 'info';

const logger = createLogger({
    level:  logLevel,
    format: combine(
        timestamp(),
        myFormat
    ),
    transports: [
    //
    // - Write to all logs with level `info` and below to `combined.log`
    // - Write all logs error (and below) to `error.log`.
    //
        new transports.File({
            filename:         path.resolve(projectFolder, 'log', 'error.log'),
            level:            'error',
            handleExceptions: true
        }),
        new transports.File({
            filename: path.resolve(projectFolder, 'log', 'combined.log'),
            level:    logLevel,
        })
    ],

    handleExceptions: false
});

logger.add(new transports.Console({
    level:  logLevel,
    format: consoleFormat
}));

/*
 * Prints the log message and returns an exception
 */
const logWithException = (msgPrefix, errMsg, alreadyLogged = false) => {
    const err = new Error();

    if (!alreadyLogged) {
        let msgToPrint = msgPrefix;

        if (errMsg) {
            msgToPrint += `, message - ${errMsg}`;
        }

        logger.error(msgToPrint + `, stack trace - ${new Error().stack}`);

        err.logged = true;
        err.message = msgToPrint;
    } else {
        err.message = errMsg;
    }

    // End process with failure.
    process.exitCode = 1;

    return err;
};

module.exports.logger = logger;
module.exports.logWithException = logWithException;
