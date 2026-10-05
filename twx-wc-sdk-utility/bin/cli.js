#!/usr/bin/env node
const {exec} = require('child_process');

const cmdArgs = process.argv.slice(2).join(' ');
const gulpCmd = `gulp --e --wd ${process.cwd()} ${cmdArgs}`;

console.log('Starting series of gulp tasks for the wrapping...');
console.log();

exec(gulpCmd, {cwd: __dirname}, (error, stdout, stderr) => {
    if (error) {
        console.error(`exec error: ${error}`);
        return;
    }

    if (stdout) {
        console.log(stdout);
    }

    if (stderr) {
        console.error(stderr);
    }
});
