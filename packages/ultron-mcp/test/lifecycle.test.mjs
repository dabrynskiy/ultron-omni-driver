import assert from 'node:assert/strict';
import {once} from 'node:events';
import {mkdtemp, writeFile, readFile, copyFile, chmod, rm, access} from 'node:fs/promises';
import {createServer} from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {setTimeout as delay} from 'node:timers/promises';
import {fileURLToPath} from 'node:url';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

import {parseBootstrapConfig} from '../lifecycle.mjs';

const cli = fileURLToPath(new URL('../bin/ultron-mcp.mjs', import.meta.url));
const snapshot = {status: 'captured', roots: [], truncated: false, errors: []};

async function fixture(t, initial = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'ultron-managed-test-'));
  const adb = join(directory, 'fake-adb.mjs');
  await copyFile(new URL('./fixtures/fake-adb.mjs', import.meta.url), adb);
  await chmod(adb, 0o700);
  await writeFile(join(directory, 'driver with spaces.apk'), 'fixture');
  await writeFile(join(directory, 'adb.jsonl'), '');
  let options = {...initial};
  let sessionId = null;
  let loseResponse = false;
  const requests = [];
  const server = createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) {
      body += chunk;
    }
    requests.push({method: req.method, path: req.url, body: body ? JSON.parse(body) : null});
    let value = null;
    if (req.url === '/status') {
      try {
        await access(join(directory, 'instrumentation.pid'));
      } catch {
        res.destroy();
        return;
      }
      if (options.neverReady) {
        res.destroy();
        return;
      }
      value = {ready: true, build: {packageName: options.serverWrongTarget ? 'wrong.app' : 'test.app', version: '1.0'}};
    } else if (req.url === '/sessions') {
      value = options.foreignSession ? [{id: 'foreign'}] : sessionId ? [{id: sessionId}] : [];
    } else if (req.url === '/session') {
      sessionId = 'managed-session';
    } else if (req.method === 'DELETE') {
      sessionId = null;
    } else if (req.url.endsWith('/ultron_snapshot')) {
      value = snapshot;
    } else if (req.url.endsWith('/appium/settings')) {
      value = {ultronSnapshotEnabled: true};
    } else if (req.url.endsWith('/ultron_act')) {
      if (loseResponse) {
        res.destroy();
        return;
      }
      value = {operation: {status: 'success'}, snapshot};
    } else {
      throw new Error(`Unexpected HTTP route ${req.url}`);
    }
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({sessionId, value}));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const configure = async (changes) => {
    options = {...options, ...changes, port: server.address().port};
    await writeFile(join(directory, 'fixture.json'), JSON.stringify(options));
  };
  await configure({});
  const config = {
    driverApk: './driver with spaces.apk',
    deviceSerial: 'test-device',
    adbPath: './fake-adb.mjs',
    appPackage: 'test.app',
    appActivity: '.Main',
    autoLaunch: true,
    startupTimeoutMs: 1500,
    adbTimeoutMs: 2000,
  };
  await writeFile(join(directory, 'device.json'), JSON.stringify(config));
  const writeConfig = async (changes) => {
    Object.assign(config, changes);
    await writeFile(join(directory, 'device.json'), JSON.stringify(config));
  };
  const connections = [];
  const connect = async () => {
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [cli, '--bootstrap-config', join(directory, 'device.json'), '--timeout-ms', '2000'],
      stderr: 'pipe',
    });
    const client = new Client({name: 'managed-test', version: '1'});
    let stderr = '';
    transport.stderr.on('data', (data) => {
      stderr += data;
    });
    await client.connect(transport);
    const connection = {
      client,
      transport,
      stderr: () => stderr,
      call: (name, args = {}) => client.callTool({name, arguments: args}, undefined, {timeout: 15000}),
    };
    connections.push(connection);
    return connection;
  };
  const commands = async () =>
    (await readFile(join(directory, 'adb.jsonl'), 'utf8')).trim().split('\n').filter(Boolean).map(JSON.parse);
  t.after(async () => {
    for (const {client} of connections) {
      await client.close();
    }
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    await rm(directory, {recursive: true, force: true});
  });
  return {
    connect,
    configure,
    writeConfig,
    commands,
    requests,
    config,
    directory,
    loseResponse: () => {
      loseResponse = true;
    },
  };
}

async function waitFor(check, timeout = 6000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) {
      return;
    }
    await delay(30);
  }
  assert.fail('Timed out waiting for cleanup');
}

test(
  'managed stdio: lazy preparation, concurrent calls, once-only install, close/reopen and no mutation retries',
  {timeout: 25000},
  async (t) => {
    const f = await fixture(t);
    const {client, call} = await f.connect();
    assert.equal((await client.listTools()).tools.length, 19);
    assert.equal((await call('ultron_session', {action: 'status'})).structuredContent.phase, 'idle');
    assert.equal((await call('ultron_snapshot', {maxDepth: -1})).isError, true);
    assert.equal((await call('ultron_snapshot', {sessionId: 'foreign'})).isError, true);
    assert.equal((await call('unknown_tool')).isError, true);
    await call('ultron_check_matcher', {backend: 'espresso', using: 'text', value: 'test', snapshot});
    await call('ultron_session', {action: 'close'});
    assert.deepEqual(await f.commands(), [], 'Discovery, status, invalid and offline calls must not run adb');

    const results = await Promise.all([call('ultron_snapshot'), call('ultron_snapshot')]);
    for (const result of results) {
      assert.deepEqual(result.structuredContent, snapshot);
    }
    let commands = await f.commands();
    assert.equal(commands.filter((args) => args.includes('install')).length, 1);
    assert.equal(commands.filter((args) => args.includes('instrument')).length, 1);
    assert.ok(commands.some((args) => args.join(' ') === '-s test-device forward tcp:0 tcp:6791'));
    assert.deepEqual(f.requests.find((r) => r.path === '/session').body.capabilities.alwaysMatch, {
      appPackage: 'test.app',
      appActivity: '.Main',
      autoLaunch: true,
    });
    assert.equal(f.requests.filter((r) => r.path === '/session').length, 1);
    const status = (await call('ultron_session', {action: 'status'})).structuredContent;
    assert.equal(status.phase, 'ready');
    assert.match(status.url, /^http:\/\/127.0.0.1:\d+$/);
    assert.equal((await call('ultron_session', {action: 'create'})).isError, true);
    assert.equal((await call('ultron_session', {action: 'attach', sessionId: 'foreign'})).isError, true);

    f.loseResponse();
    const result = await call('ultron_act', {matcher: {name: 'withText', args: ['Save']}, action: 'click'});
    assert.equal(result.isError, true);
    assert.equal(f.requests.filter((r) => r.path.endsWith('/ultron_act')).length, 1);
    assert.equal((await call('ultron_session', {action: 'close', sessionId: 'foreign'})).isError, true);
    const closed = await call('ultron_session', {action: 'close'});
    assert.equal(closed.isError, false);
    assert.equal(closed.structuredContent.restartInstrumentationRequired, false);
    assert.equal((await call('ultron_session', {action: 'close'})).isError, false);
    assert.equal((await call('ultron_session', {action: 'create', autoLaunch: false})).isError, false);
    commands = await f.commands();
    assert.equal(commands.filter((args) => args.includes('install')).length, 1);
    assert.equal(commands.filter((args) => args.includes('instrument')).length, 2);
    assert.equal(commands.filter((args) => args.includes('--remove')).length, 1);
    assert.equal((await call('ultron_session', {action: 'close'})).isError, false);
  },
);

for (const [name, options, expected, installed] of [
  ['signature mismatch', {installFailure: true}, /INSTALL_FAILED_UPDATE_INCOMPATIBLE/, false],
  ['foreign instrumentation', {busy: true}, /already has active instrumentation/, false],
  ['missing target', {missingApp: true}, /not installed/, false],
  ['unauthorized device', {devices: 'test-device\tunauthorized\n'}, /offline or unauthorized/, false],
  ['wrong APK target', {wrongTarget: true}, /does not register/, true],
  ['runner crash', {crash: true}, /Process crashed/, true],
  ['wrong server target', {serverWrongTarget: true}, /target package does not match/, true],
  ['foreign Android HTTP session', {foreignSession: true}, /already has a session/, true],
  ['readiness timeout', {neverReady: true}, /did not become ready/, true],
]) {
  test(`managed startup rejects ${name} and rolls back owned resources`, {timeout: 15000}, async (t) => {
    const f = await fixture(t, options);
    const {call} = await f.connect();
    const result = await call('ultron_snapshot');
    assert.equal(result.isError, true);
    assert.match(result.content[0].text, expected);
    const status = (await call('ultron_session', {action: 'status'})).structuredContent;
    assert.equal(status.phase, 'failed');
    assert.equal(status.installed, installed);
    assert.equal(status.url, null);
    const before = (await f.commands()).length;
    assert.equal((await call('ultron_snapshot')).isError, true);
    assert.equal((await f.commands()).length, before, 'Failed session needs explicit close before restart');
    assert.equal((await call('ultron_session', {action: 'close'})).isError, false);
  });
}

test('auto-selection requires one authorized device, then keeps its identity', {timeout: 15000}, async (t) => {
  const f = await fixture(t, {devices: 'test-device\tdevice\nother-device\tdevice\n'});
  await f.writeConfig({deviceSerial: undefined});
  const {call} = await f.connect();
  assert.match((await call('ultron_snapshot')).content[0].text, /exactly one authorized/);
  await call('ultron_session', {action: 'close'});
  await f.configure({devices: 'test-device\tdevice\n'});
  assert.equal((await call('ultron_snapshot')).isError, false);
  await call('ultron_session', {action: 'close'});
  await f.configure({devices: 'other-device\tdevice\n'});
  assert.match((await call('ultron_snapshot')).content[0].text, /test-device is unavailable/);
});

for (const failure of ['removeFailure', 'stopFailure']) {
  test(`cleanup retains ownership and can retry after ${failure}`, {timeout: 15000}, async (t) => {
    const f = await fixture(t);
    const first = await f.connect();
    assert.equal((await first.call('ultron_snapshot')).isError, false);
    await f.configure({[failure]: true});
    assert.equal((await first.call('ultron_session', {action: 'close'})).isError, true);
    assert.equal((await first.call('ultron_session', {action: 'status'})).structuredContent.phase, 'failed');
    const second = await f.connect();
    assert.match((await second.call('ultron_snapshot')).content[0].text, /locked by another/);
    await second.call('ultron_session', {action: 'close'});
    await f.configure({[failure]: false});
    assert.equal((await first.call('ultron_session', {action: 'close'})).isError, false);
    assert.equal((await first.call('ultron_snapshot')).isError, false);
    assert.equal((await f.commands()).filter((args) => args.includes('install')).length, 1);
    await first.call('ultron_session', {action: 'close'});
  });
}

test('cancelling preparation rolls back without executing the requested UI action', {timeout: 15000}, async (t) => {
  const f = await fixture(t, {neverReady: true});
  const {client, call} = await f.connect();
  const controller = new AbortController();
  const cancelled = client.callTool(
    {name: 'ultron_act', arguments: {matcher: {name: 'withText', args: ['Save']}, action: 'click'}},
    undefined,
    {signal: controller.signal, timeout: 10000},
  );
  const rejected = assert.rejects(cancelled);
  await waitFor(async () => (await f.commands()).some((args) => args.includes('instrument')));
  controller.abort();
  await rejected;
  await waitFor(async () => (await f.commands()).some((args) => args.includes('--remove')));
  assert.equal(f.requests.filter((r) => r.path.endsWith('/ultron_act')).length, 0);
  assert.equal((await call('ultron_session', {action: 'status'})).structuredContent.phase, 'failed');
  await call('ultron_session', {action: 'close'});
});

test('a stopped instrumentation child is reported without an implicit app restart', {timeout: 15000}, async (t) => {
  const f = await fixture(t);
  const {call} = await f.connect();
  await call('ultron_snapshot');
  process.kill(Number(await readFile(join(f.directory, 'instrumentation.pid'), 'utf8')), 'SIGTERM');
  await waitFor(async () => (await call('ultron_session', {action: 'status'})).structuredContent.phase === 'failed');
  assert.equal((await call('ultron_snapshot')).isError, true);
  assert.equal((await f.commands()).filter((args) => args.includes('instrument')).length, 1);
  await call('ultron_session', {action: 'close'});
});

test('explicit create validates merged launch options before touching the device', {timeout: 15000}, async (t) => {
  const f = await fixture(t);
  await f.writeConfig({appActivity: undefined, autoLaunch: false});
  const {call} = await f.connect();
  assert.equal((await call('ultron_session', {action: 'create', autoLaunch: true})).isError, true);
  assert.equal((await call('ultron_session', {action: 'create', appPackage: 'another.app'})).isError, true);
  assert.deepEqual(await f.commands(), []);
  assert.equal(
    (await call('ultron_session', {action: 'create', autoLaunch: true, appActivity: '.Other'})).isError,
    false,
  );
  assert.equal(f.requests.find((r) => r.path === '/session').body.capabilities.alwaysMatch.appActivity, '.Other');
  await call('ultron_session', {action: 'close'});
});

test('device ownership prevents a second MCP process from replacing instrumentation', {timeout: 15000}, async (t) => {
  const f = await fixture(t);
  const first = await f.connect();
  const second = await f.connect();
  assert.equal((await first.call('ultron_snapshot')).isError, false);
  const rejected = await second.call('ultron_snapshot');
  assert.equal(rejected.isError, true);
  assert.match(rejected.content[0].text, /locked by another/);
  assert.equal((await f.commands()).filter((args) => args.includes('install')).length, 1);
  await second.call('ultron_session', {action: 'close'});
  await first.call('ultron_session', {action: 'close'});
  assert.equal((await second.call('ultron_snapshot')).isError, false);
  await second.call('ultron_session', {action: 'close'});
});

for (const mode of ['EOF', 'SIGTERM', 'during preparation']) {
  test(`managed CLI cleans up on ${mode}`, {timeout: 15000}, async (t) => {
    const f = await fixture(t, {neverReady: mode === 'during preparation'});
    const {client, transport, call} = await f.connect();
    if (mode === 'during preparation') {
      const pending = call('ultron_snapshot').catch(() => {});
      await waitFor(async () => (await f.commands()).some((args) => args.includes('instrument')));
      // Closing the SDK client ends stdin before escalating to signals.
      await client.close();
      await pending;
    } else {
      assert.equal((await call('ultron_snapshot')).isError, false);
      if (mode === 'EOF') {
        await client.close();
      } else {
        process.kill(transport.pid, 'SIGTERM');
      }
    }
    await waitFor(async () => (await f.commands()).some((args) => args.includes('--remove')));
    await assert.rejects(access(join(f.directory, 'instrumentation.pid')));
    // Ownership was released: a new MCP process can prepare the same device.
    await f.configure({neverReady: false});
    const next = await f.connect();
    assert.equal((await next.call('ultron_snapshot')).isError, false);
    await next.call('ultron_session', {action: 'close'});
  });
}

test('bootstrap config validates early and resolves APK paths relative to its file', () => {
  assert.equal(
    parseBootstrapConfig({driverApk: './driver.apk', appPackage: 'test.app'}, '/tmp/config').driverApk,
    '/tmp/config/driver.apk',
  );
  assert.throws(() => parseBootstrapConfig({driverApk: 'x', appPackage: 'test.app', autoLaunch: true}), /appActivity/);
  assert.throws(() => parseBootstrapConfig({driverApk: 'x', appPackage: 'test.app; touch bad'}));
  assert.throws(() => parseBootstrapConfig({driverApk: 'x', appPackage: 'test.app', devicePort: 0}));
  assert.throws(() => parseBootstrapConfig({driverApk: 'x', appPackage: 'test.app', typo: true}));
});
