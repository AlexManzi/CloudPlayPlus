const { spawnSync } = require('node:child_process');
const { resolve } = require('node:path');

const result = spawnSync(process.execPath,
    ['--test', resolve(__dirname, '../app/src/test/js/gxcloud_inject.test.cjs')], {
        stdio: 'inherit',
        env: {
            ...process.env,
            GXCLOUD_SCRIPT_PATH: resolve(__dirname,
                '../app/build/generated/minifiedInject/release/raw/gxcloud_inject.js')
        }
    });
if (result.error) throw result.error;
process.exitCode = result.status === null ? 1 : result.status;
