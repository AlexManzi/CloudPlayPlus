if (Number(process.versions.node.split('.')[0]) < 18) {
    console.error(`Release JavaScript generation requires Node.js 18+; found ${process.version}. ` +
        'Set nodeExecutable=/absolute/path/to/node in local.properties or pass -PnodeExecutable to Gradle.');
    process.exit(1);
}

const { readFileSync, mkdirSync, writeFileSync } = require('node:fs');
const { dirname } = require('node:path');
const { minify } = require('terser');

async function main() {
    const [input, output] = process.argv.slice(2);
    if (!input || !output) throw new Error('Usage: node minify-inject.cjs INPUT OUTPUT');
    const result = await minify(readFileSync(input, 'utf8'), {
        ecma: 2020,
        compress: false,
        mangle: { properties: false },
        keep_fnames: true,
        keep_classnames: true,
        format: { comments: false, beautify: false, quote_style: 3 }
    });
    if (!result.code) throw new Error('Minification produced no script');
    mkdirSync(dirname(output), { recursive: true });
    writeFileSync(output, result.code + '\n');
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
