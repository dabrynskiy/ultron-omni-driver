import {execFile} from 'node:child_process';
import {mkdtemp, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cache = await mkdtemp(path.join(tmpdir(), 'ultron-package-check-'));
try {
  // Ignore lifecycle scripts to avoid recursively calling this prepack check.
  const {stdout} = await promisify(execFile)(
    process.platform === 'win32' ? 'npm.cmd' : 'npm',
    ['pack', '--dry-run', '--json', '--ignore-scripts', '--cache', cache],
    {cwd: root, maxBuffer: 8 * 1024 * 1024},
  );
  /** @type {Array<{files: Array<{path: string}>}>} */
  const packages = JSON.parse(stdout);
  const [{files}] = packages;
  const forbidden = files.filter(
    ({path: name}) =>
      /(?:^|\/)(?:\.idea|\.gradle|\.kotlin)(?:\/|$)/.test(name) ||
      /^espresso-server\/.*\/build\//.test(name) ||
      /\.(?:apk|aab|jks|keystore|p12|pfx|pem|key|iml|log)$/i.test(name) ||
      /(?:^|\/)(?:\.env(?:\..*)?|local\.properties)$/.test(name),
  );
  if (forbidden.length) {
    throw new Error(
      `Refusing to package local artifacts or credentials:\n${forbidden.map((file) => file.path).join('\n')}`,
    );
  }
  for (const required of [
    'LICENSE',
    'NOTICE',
    'build/lib/index.js',
    'espresso-server/buildSrc/build.gradle.kts',
    'espresso-server/buildSrc/src/main/kotlin/io/appium/espressoserver/gradle/CapabilityVersion.kt',
  ]) {
    if (!files.some((file) => file.path === required)) {
      throw new Error(`Package is missing ${required}`);
    }
  }
  process.stdout.write(
    `Package check passed (${files.length} files; no local APKs, credential files or build caches).\n`,
  );
} finally {
  await rm(cache, {recursive: true, force: true});
}
