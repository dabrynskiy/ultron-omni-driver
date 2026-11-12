import assert from 'node:assert/strict';
import {once} from 'node:events';
import {readFileSync} from 'node:fs';
import {createServer} from 'node:http';
import {createRequire} from 'node:module';
import {dirname, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

import UltronMcpPlugin from '../index.mjs';

// Resolve the host's published entry points without coupling this package to its private modules.
const hostRequire = createRequire(
  process.env.APPIUM_MCP_TEST_ROOT ? join(process.env.APPIUM_MCP_TEST_ROOT, 'package.json') : import.meta.url,
);
const packagePath = hostRequire.resolve('appium-mcp/package.json');
const hostPackage = JSON.parse(readFileSync(packagePath, 'utf8'));
const hostRoot = dirname(packagePath);
const {verifyAppiumMcpNames} = await import(pathToFileURL(join(hostRoot, hostPackage.exports['./core'].import)));
const report = verifyAppiumMcpNames({plugins: [new UltronMcpPlugin()]});
assert.equal(report.ok, true, JSON.stringify(report));
const {Client} = await import(pathToFileURL(hostRequire.resolve('@modelcontextprotocol/sdk/client/index.js')));
const {StdioClientTransport} = await import(
  pathToFileURL(hostRequire.resolve('@modelcontextprotocol/sdk/client/stdio.js'))
);

const requests = [];
const snapshot = {
  schemaVersion: 1,
  backend: 'espresso',
  status: 'captured',
  snapshotId: 'smoke',
  roots: [{type: 'View', children: [{type: 'SemanticsNode', node_id: 42, test_tag: 'compose-button'}]}],
  nodeCount: 2,
  truncated: false,
  errors: [],
  capturedAtMs: 1,
  durationMs: 1,
};
let settings = {ultronSnapshotEnabled: false};
const fixture = createServer(async (request, response) => {
  let body = '';
  for await (const chunk of request) {
    body += chunk;
  }
  const args = body ? JSON.parse(body) : undefined;
  requests.push({method: request.method, path: request.url, args});
  let value;
  if (
    request.method === 'GET' &&
    ['/session/smoke', '/session/smoke/appium/session_capabilities'].includes(request.url)
  ) {
    value = {platformName: 'Android', 'appium:automationName': 'Espresso'};
  } else if (request.url === '/session/smoke/appium/settings') {
    if (args) {
      settings = {...settings, ...args.settings};
    }
    value = settings;
  } else if (request.url === '/session/smoke/execute/sync') {
    if (args.script === 'mobile: ultronSnapshot') {
      value = snapshot;
    } else if (args.script === 'mobile: ultronLastResult') {
      value = null;
    } else {
      value = {
        operation: {
          status: ['mobile: ultronAssert', 'mobile: ultronComposeAssert'].includes(args.script) ? 'failure' : 'success',
          error: ['mobile: ultronAssert', 'mobile: ultronComposeAssert'].includes(args.script)
            ? 'Expected state missing'
            : null,
        },
        snapshot,
      };
    }
  } else {
    response.writeHead(404, {'Content-Type': 'application/json'});
    response.end(JSON.stringify({value: {error: 'unknown command', message: request.url}}));
    return;
  }
  response.writeHead(200, {'Content-Type': 'application/json'});
  response.end(JSON.stringify({value}));
});
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const client = new Client({name: 'ultron-plugin-smoke', version: '0.1.0'});
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [
    join(hostRoot, hostPackage.bin['appium-mcp']),
    '--plugin',
    fileURLToPath(new URL('../index.mjs', import.meta.url)),
  ],
  env: {...process.env, NO_UI: 'true', WDIO_LOG_LEVEL: 'error', APPIUM_MCP_ON_CLIENT_DISCONNECT: 'skip'},
  stderr: 'pipe',
});
let stderr = '';
transport.stderr?.on('data', (chunk) => {
  stderr += chunk;
});
try {
  await client.connect(transport);
  const {tools} = await client.listTools();
  assert.equal(tools.filter((tool) => tool.name.startsWith('ultron_')).length, 21);
  const missing = await client.callTool({name: 'ultron_snapshot', arguments: {sessionId: 'missing'}});
  assert.equal(missing.isError, true);
  const attach = await client.callTool({
    name: 'appium_session_management',
    arguments: {
      action: 'attach',
      sessionId: 'smoke',
      remoteServerUrl: `http://127.0.0.1:${fixture.address().port}`,
    },
  });
  assert.notEqual(attach.isError, true, JSON.stringify(attach));
  const observed = await client.callTool({
    name: 'ultron_snapshot',
    arguments: {sessionId: 'smoke', useUnmergedTree: false},
  });
  assert.notEqual(observed.isError, true, JSON.stringify(observed));
  assert.deepEqual(observed.structuredContent, snapshot);
  const matcher = {class: 'ViewMatchers', name: 'withText', args: ['Save']};
  const acted = await client.callTool({
    name: 'ultron_act',
    arguments: {sessionId: 'smoke', matcher, action: 'click', timeoutMs: 0},
  });
  assert.equal(acted.structuredContent.operation.status, 'success', JSON.stringify(acted));
  const recyclerArgs = {matcher, action: 'click', position: 0, childMatcher: matcher, timeoutMs: 0};
  const recycled = await client.callTool({name: 'ultron_recycler', arguments: {sessionId: 'smoke', ...recyclerArgs}});
  assert.equal(recycled.structuredContent.operation.status, 'success', JSON.stringify(recycled));
  assert.deepEqual(requests.at(-1).args, {script: 'mobile: ultronRecycler', args: [recyclerArgs]});
  const composeArgs = {
    matcher: {name: 'hasTestTag', args: ['compose-button']},
    action: 'scrollToIndex',
    options: {index: 0},
    useUnmergedTree: false,
  };
  const composeActed = await client.callTool({
    name: 'ultron_compose_act',
    arguments: {sessionId: 'smoke', ...composeArgs},
  });
  assert.equal(composeActed.isError, false, JSON.stringify(composeActed));
  assert.deepEqual(requests.at(-1).args, {script: 'mobile: ultronComposeAct', args: [composeArgs]});
  const composeAsserted = await client.callTool({
    name: 'ultron_compose_assert',
    arguments: {matcher: composeArgs.matcher, assertion: 'isOn'},
  });
  assert.equal(composeAsserted.isError, true);
  assert.deepEqual(composeAsserted.structuredContent.snapshot, snapshot);
  const asserted = await client.callTool({
    name: 'ultron_assert',
    arguments: {sessionId: 'smoke', matcher, exists: false, timeoutMs: 0},
  });
  assert.equal(asserted.isError, true);
  assert.deepEqual(asserted.structuredContent.snapshot, snapshot);
  const named = await client.callTool({
    name: 'ultron_assert',
    arguments: {
      sessionId: 'smoke',
      matcher,
      assertion: 'hasText',
      text: '',
      timeoutMs: 0,
    },
  });
  assert.equal(named.isError, true);
  assert.deepEqual(named.structuredContent.snapshot, snapshot);
  assert.deepEqual(requests.at(-1).args, {
    script: 'mobile: ultronAssert',
    args: [{matcher, condition: {class: 'ViewMatchers', name: 'withText', args: ['']}, timeoutMs: 0}],
  });
  const beforeInvalidAssertion = requests.length;
  // The host validates schemas before plugin execution and reports invalid input as a protocol error.
  await assert.rejects(
    client.callTool({
      name: 'ultron_assert',
      arguments: {
        sessionId: 'smoke',
        matcher,
        assertion: 'hasText',
      },
    }),
    /require exactly one of text or resourceId/,
  );
  assert.equal(requests.length, beforeInvalidAssertion);
  assert.equal(settings.ultronSnapshotEnabled, true);
  assert.deepEqual(requests.find((request) => request.args?.script === 'mobile: ultronAct').args, {
    script: 'mobile: ultronAct',
    args: [{matcher, action: 'click', timeoutMs: 0}],
  });
  assert.equal(
    requests.some((request) => request.method === 'DELETE'),
    false,
  );
  process.stdout.write(
    `Appium MCP ${hostPackage.version}: plugin registration, stdio discovery, remote session, snapshot, action and failed assertion passed.\n`,
  );
} catch (error) {
  process.stderr.write(stderr);
  throw error;
} finally {
  await client.close();
  await transport.close();
  fixture.closeAllConnections();
  fixture.close();
}
