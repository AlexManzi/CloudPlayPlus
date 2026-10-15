# Injected JavaScript build tools

Use Node.js 18+ and run `npm ci` from the repository root. Terser is pinned by
`package-lock.json` and runs only at build time; no npm dependencies enter the APK.

- `npm run test:js` tests the readable main resource.
- `npm run test:js:release` generates and tests the release script.
- Release Gradle resource tasks automatically run `minifyReleaseInject` and overlay
  the main script with `app/build/generated/minifiedInject/release/raw/gxcloud_inject.js`.
  Debug continues to use the readable resource.

The minifier shortens local identifiers and removes comments and formatting.
Compression transformations and property mangling remain disabled; function/class
names, window exports, DOM markers, external property names, and shader string values
are preserved. Do not edit generated output.

Gradle uses `node` on PATH by default. If Android Studio picks an older Node,
add `nodeExecutable=/absolute/path/to/node` to the project's ignored `local.properties`.
This keeps machine-specific paths out of committed build configuration. Alternatively,
pass `-PnodeExecutable=/absolute/path/to/node` or set the Gradle property; either
overrides `local.properties`. Node.js 18+ is required for generation and JS tests.
