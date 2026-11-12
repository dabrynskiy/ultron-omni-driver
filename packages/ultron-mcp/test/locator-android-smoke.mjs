import assert from 'node:assert/strict';
import {execFile, spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {mkdir, writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs, promisify} from 'node:util';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

// Install :library:assembleComposeOnDebugAndroidTest first. Always require an explicit device.
const {values} = parseArgs({options: {serial: {type: 'string'}, output: {type: 'string'}}});
assert.ok(values.serial, '--serial is required');
const runId = randomUUID();
const output = values.output ?? join('/private/tmp', `ultron-locator-device-${runId}`);
await mkdir(output, {recursive: true});
const packageName = 'io.appium.espressoserver.lib.test';
const execute = promisify(execFile);
const adb = async (...args) => (await execute('adb', ['-s', values.serial, ...args], {timeout: 15000})).stdout.trim();
const fixture = spawn(
  'adb',
  [
    '-s',
    values.serial,
    'shell',
    'am',
    'instrument',
    '-w',
    '-r',
    '-e',
    'class',
    'io.appium.espressoserver.test.ultron.SnapshotLocatorDeviceTest',
    '-e',
    'locatorRunId',
    runId,
    `${packageName}/androidx.test.runner.AndroidJUnitRunner`,
  ],
  {stdio: ['ignore', 'pipe', 'pipe']},
);
let instrumentationLog = '';
fixture.stdout.on('data', (data) => {
  instrumentationLog += data;
});
fixture.stderr.on('data', (data) => {
  instrumentationLog += data;
});
const finished = new Promise((resolve, reject) => {
  fixture.on('error', reject);
  fixture.on('exit', resolve);
});
const client = new Client({name: 'snapshot-locator-android-smoke', version: '1'});
let transport;
let port;
const reports = [];
const call = async (name, args = {}) => {
  const result = await client.callTool({name, arguments: args});
  assert.notEqual(result.isError, true, JSON.stringify(result));
  return result.structuredContent;
};
const verify = async (label, args, status, count) => {
  const result = await client.callTool({name: 'ultron_check_matcher', arguments: args});
  const report = result.structuredContent;
  assert.equal(report?.status, status, `${label}: ${JSON.stringify(result)}`);
  assert.equal(result.isError, status !== 'unique', label);
  if (count !== undefined) {
    assert.equal(report.observedCount, count, label);
  }
  reports.push({label, ...report});
  process.stdout.write(`${label}: ${report.status} (${report.observedCount})\n`);
  return report;
};
const flatten = (roots) => roots.flatMap((node) => [node, ...flatten(node.children ?? [])]);
try {
  let ready;
  const deadline = Date.now() + 45000;
  while (!ready && Date.now() < deadline) {
    if (fixture.exitCode !== null) {
      throw new Error(`Fixture stopped early: ${instrumentationLog}`);
    }
    try {
      ready = JSON.parse(await adb('exec-out', 'run-as', packageName, 'cat', `files/locator-ready-${runId}.json`));
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  assert.ok(ready, `Fixture did not become ready: ${instrumentationLog}`);
  await writeFile(join(output, 'references.json'), JSON.stringify(ready, null, 2));
  port = await adb('forward', 'tcp:0', `tcp:${ready.port}`);
  transport = new StdioClientTransport({
    command: process.execPath,
    args: [fileURLToPath(new URL('../bin/ultron-mcp.mjs', import.meta.url)), '--url', `http://127.0.0.1:${port}`],
    stderr: 'pipe',
  });
  await client.connect(transport);
  const tools = (await client.listTools()).tools;
  assert.deepEqual(tools.find((tool) => tool.name === 'ultron_check_matcher').inputSchema.properties.backend.enum, [
    'espresso',
    'compose',
    'automator',
    'dom',
  ]);
  await call('ultron_session', {action: 'attach', sessionId: ready.sessionId});
  const before = await call('ultron_settings');
  for (const [text, count] of Object.entries(ready.nativeCounts)) {
    await verify(
      `fresh UIAutomator ${text}`,
      {backend: 'automator', using: 'text', value: text},
      count === 1 ? 'unique' : count === 0 ? 'not_found' : 'ambiguous',
      count,
    );
  }
  await verify(
    'fresh UiSelector conjunction',
    {
      backend: 'automator',
      using: '-android uiautomator',
      value: 'new UiSelector().text("Locator unique").enabled(true).className("android.widget.Button")',
    },
    'unique',
    1,
  );
  await verify(
    'fresh accessibility description',
    {backend: 'automator', using: 'accessibility id', value: 'Locator unique'},
    'unique',
    1,
  );
  const snapshots = {};
  for (const mode of ['ultron', 'automator']) {
    const snapshot = await call('ultron_snapshot', {mode});
    assert.equal(snapshot.status, 'captured', JSON.stringify(snapshot.errors));
    assert.equal(snapshot.truncated, false);
    assert.equal(flatten(snapshot.roots).length, snapshot.nodeCount);
    assert.ok(!JSON.stringify(snapshot).includes('locator-secret-932'), 'Password leaked');
    snapshots[mode] = snapshot;
    await writeFile(join(output, `${mode}.json`), JSON.stringify(snapshot, null, 2));
    for (const [value, reference] of [
      ['#submit', 'submit'],
      ['button[aria-label="Duplicate"]', 'duplicate'],
      ['#locator-missing', 'missing'],
    ]) {
      const count = ready.domCounts[reference];
      await verify(
        `${mode} DOM ${value}`,
        {snapshot, backend: 'dom', using: 'css selector', value},
        count === 1 ? 'unique' : count === 0 ? 'not_found' : 'ambiguous',
        count,
      );
    }
    const nodes = flatten(snapshot.roots);
    for (const [text, reference] of [
      ['Main', 'shared'],
      ['Shadow', 'shadow'],
      ['Frame', 'frame'],
    ]) {
      const node = nodes.find((node) => node.type === 'DomNode' && node.text === text);
      assert.ok(node, `Missing ${text} DOM node`);
      const report = await verify(
        `${mode} DOM ${text} context`,
        {snapshot, backend: 'dom', using: 'css selector', value: '#shared', scope: {context: node.context}},
        'unique',
        ready.domCounts[reference],
      );
      await verify(
        `${mode} DOM ${text} structural path`,
        {
          snapshot,
          backend: 'dom',
          using: 'css selector',
          value: node.selector,
          scope: {webview: report.matches[0].webview, context: node.context},
        },
        'unique',
        1,
      );
    }
  }
  await verify(
    'fresh DOM unique',
    {backend: 'dom', using: 'css selector', value: '#submit'},
    'unique',
    ready.domCounts.submit,
  );
  await verify(
    'fresh DOM duplicate',
    {backend: 'dom', using: 'css selector', value: 'button[aria-label="Duplicate"]', limit: 1},
    'ambiguous',
    ready.domCounts.duplicate,
  );
  const native = await verify(
    'saved UIAutomator window',
    {snapshot: snapshots.automator, backend: 'automator', using: 'text', value: 'Locator unique'},
    'unique',
    1,
  );
  await verify(
    'scoped UIAutomator window',
    {
      snapshot: snapshots.automator,
      backend: 'automator',
      using: 'text',
      value: 'Locator unique',
      scope: {windowId: native.matches[0].window.id},
    },
    'unique',
    1,
  );
  for (const [backend, mode, using, value] of [
    ['automator', 'automator', 'text', 'Locator unique'],
    ['dom', 'ultron', 'id', 'submit'],
  ]) {
    const partial = await call('ultron_snapshot', {mode, maxNodes: 2});
    assert.equal(partial.truncated, true);
    await writeFile(join(output, `${mode}-partial.json`), JSON.stringify(partial, null, 2));
    await verify(`${backend} real partial tree`, {snapshot: partial, backend, using, value}, 'indeterminate');
  }
  await adb('shell', 'run-as', packageName, 'touch', `files/locator-sensitive-${runId}`);
  let sensitiveReady = false;
  const sensitiveDeadline = Date.now() + 15000;
  while (!sensitiveReady && Date.now() < sensitiveDeadline) {
    try {
      sensitiveReady =
        (await adb('exec-out', 'run-as', packageName, 'cat', `files/locator-sensitive-ready-${runId}`)) === 'ready';
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
  }
  assert.ok(sensitiveReady, 'Sensitive fixture did not become ready');
  for (const mode of ['ultron', 'automator']) {
    const snapshot = await call('ultron_snapshot', {mode});
    assert.equal(snapshot.status, 'captured');
    assert.ok(flatten(snapshot.roots).some((node) => node.sensitive === true));
    assert.ok(!JSON.stringify(snapshot).includes('locator-secret-932'), 'Password leaked');
    await writeFile(join(output, `${mode}-redacted.json`), JSON.stringify(snapshot, null, 2));
    if (mode === 'automator') {
      await verify(
        'redacted accessibility text',
        {snapshot, backend: 'automator', using: 'text', value: 'locator-secret-932'},
        'indeterminate',
      );
    }
    await verify(
      `${mode} redacted DOM property`,
      {snapshot, backend: 'dom', using: 'css selector', value: 'input[name="secret"]'},
      'indeterminate',
    );
  }
  await verify(
    'unsupported UiSelector regex',
    {
      snapshot: snapshots.automator,
      backend: 'automator',
      using: '-android uiautomator',
      value: 'new UiSelector().textMatches(".*")',
    },
    'unsupported',
  );
  await verify(
    'unsupported CSS class',
    {snapshot: snapshots.ultron, backend: 'dom', using: 'css selector', value: '.not-collected'},
    'unsupported',
  );
  await verify(
    'Espresso cannot use accessibility tree',
    {snapshot: snapshots.automator, backend: 'espresso', using: 'text', value: 'Locator unique'},
    'unsupported',
  );
  assert.deepEqual(await call('ultron_settings'), before, 'Checks changed session settings');
} finally {
  await client.close();
  if (transport) {
    await transport.close();
  }
  await adb('shell', 'run-as', packageName, 'touch', `files/locator-stop-${runId}`).catch(() => {});
  if (port) {
    await adb('forward', '--remove', `tcp:${port}`);
  }
  const timeout = setTimeout(() => fixture.kill('SIGTERM'), 15000);
  await finished;
  clearTimeout(timeout);
  await writeFile(join(output, 'reports.json'), JSON.stringify({serial: values.serial, reports}, null, 2));
  await writeFile(join(output, 'instrumentation.log'), instrumentationLog);
}
assert.match(instrumentationLog, /OK \(1 test\)/, instrumentationLog);
assert.doesNotMatch(instrumentationLog, /FAILURES!!!|INSTRUMENTATION_FAILED/);
process.stdout.write(`Passed ${reports.length} real-device MCP checks on ${values.serial}. Artifacts: ${output}\n`);
