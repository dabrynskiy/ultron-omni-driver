import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createServer} from 'node:http';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';
import {AndroidHttpClient} from '../direct-client.mjs';

test('direct client declares UTF-8 and preserves Unicode throughout the request body', async t => {
  const unicode = 'Главная Ёжик 🧪 — café 中文';
  const body = {
    matcher: {class: 'ViewMatchers', name: 'withText', args: [unicode]},
    itemMatcher: {class: 'ViewMatchers', name: 'hasDescendant', args: [
      {class: 'ViewMatchers', name: 'withText', args: [unicode]},
    ]},
    condition: {class: 'ViewMatchers', name: 'withText', args: [unicode]},
    text: unicode,
  };
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    const request = new Request(url, options);
    assert.equal(request.headers.get('content-type'), 'application/json; charset=utf-8');
    assert.deepEqual(JSON.parse(await request.text()), body);
    return Response.json({value: body});
  });
  assert.deepEqual(await new AndroidHttpClient().request('/probe', 'POST', body), body);
});

test('standalone stdio lifecycle, observations, mutations, Compose routing and errors without Appium', {timeout: 20000}, async () => {
  let sessionId = null;
  let settings = {ultronSnapshotEnabled: false, driver: 'espresso'};
  let loseResponse = false;
  const requests = [];
  const snapshot = {status: 'captured', truncated: false, errors: [], roots: [{type: 'View', text: 'Save', children: [{
    type: 'SemanticsNode', semantics_root: true, semantics_tree: 'unmerged', config: {},
    children: [{type: 'SemanticsNode', test_tag: 'save', node_id: 9, config: {Text: ['Save']}}],
  }]}]};
  const fixture = createServer(async (request, response) => {
    let text = '';
    for await (const chunk of request) {text += chunk;}
    const body = text ? JSON.parse(text) : undefined;
    requests.push({path: request.url, method: request.method, body});
    let value = null;
    if (request.url === '/status') {value = {ready: true};}
    else if (request.url === '/sessions') {value = sessionId ? [{id: sessionId}] : [];}
    else if (request.url === '/session') {sessionId = 'direct-session';}
    else if (request.url === '/session/direct-session') {
      if (request.method === 'DELETE') {sessionId = null;}
    }
    else if (request.url === '/session/direct-session/appium/settings') {
      if (body) {settings = {...settings, ...body.settings};}
      value = settings;
    }
    else if (request.url.endsWith('/ultron_snapshot')) {value = snapshot;}
    else if (request.url.endsWith('/ultron_act') || request.url.endsWith('/ultron_recycler') || request.url.endsWith('/ultron_compose_act')) {
      if (loseResponse) {request.socket.destroy(); return;}
      value = {operation: {status: 'success'}, snapshot};
    }
    else if (request.url.endsWith('/ultron_assert') || request.url.endsWith('/ultron_compose_assert')) {value = {operation: {status: 'failure', error: 'Missing'}, snapshot};}
    else if (request.url.endsWith('/element')) {value = {'element-6066-11e4-a52e-4f735466cecf': 'compose-node'};}
    else if (request.url.endsWith('/displayed')) {value = false;}
    else if (request.url.endsWith('/screenshot')) {value = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGmQAAAAASUVORK5CYII=';}
    else if (!request.url.endsWith('/click') && !request.url.endsWith('/back') && !request.url.endsWith('/hide_keyboard')) {
      response.writeHead(400, {'Content-Type': 'application/json'});
      response.end(JSON.stringify({value: {error: 'invalid argument', message: 'Fixture rejected command'}}));
      return;
    }
    response.writeHead(200, {'Content-Type': 'application/json'});
    response.end(JSON.stringify({sessionId, value}));
  });
  fixture.listen(0, '127.0.0.1');
  await once(fixture, 'listening');
  const url = `http://127.0.0.1:${fixture.address().port}`;
  const transport = new StdioClientTransport({command: process.execPath,
    args: [fileURLToPath(new URL('../bin/ultron-mcp.mjs', import.meta.url)), '--url', url, '--timeout-ms', '5000'],
    stderr: 'pipe',
  });
  const client = new Client({name: 'direct-test', version: '1'});
  const call = (name, args = {}) => client.callTool({name, arguments: args});
  try {
    await client.connect(transport);
    const listed = (await client.listTools()).tools;
    assert.equal(listed.length, 14);
    assert.ok(listed.find(tool => tool.name === 'ultron_assert').inputSchema.properties.assertion.enum.includes('hasText'));
    assert.equal((await call('ultron_snapshot')).isError, true);
    const status = await call('ultron_session', {action: 'status'});
    assert.deepEqual(status.structuredContent.sessions, []);
    assert.equal((await call('ultron_session', {action: 'create', autoLaunch: true, appPackage: 'test.app'})).isError, true);
    const created = await call('ultron_session', {action: 'create', appPackage: 'test.app', appActivity: '.Main', autoLaunch: true});
    assert.equal(created.structuredContent.sessionId, 'direct-session');
    assert.deepEqual(requests.find(r => r.path === '/session').body.capabilities.alwaysMatch,
      {appPackage: 'test.app', appActivity: '.Main', autoLaunch: true});
    assert.equal((await call('ultron_session', {action: 'attach', sessionId: 'direct-session'})).isError, false);
    const observed = await call('ultron_snapshot', {useUnmergedTree: false});
    assert.deepEqual(observed.structuredContent, snapshot);
    assert.equal(requests.find(r => r.path.endsWith('/ultron_snapshot')).body.useUnmergedTree, false);
    const checked = await call('ultron_check_matcher', {backend: 'espresso', using: 'text', value: 'Save'});
    assert.equal(checked.structuredContent.status, 'unique');
    const matcher = {class: 'ViewMatchers', name: 'withText', args: ['Save']};
    assert.equal((await call('ultron_act', {matcher, action: 'click'})).isError, false);
    assert.equal(settings.ultronSnapshotEnabled, true);
    for (const action of [
      {action: 'doubleClick', autoScroll: false, name: 'Save'},
      {action: 'clickTopRight', offsetX: -8, offsetY: 0},
      {action: 'pressKey', keyCode: 29, metaState: 1, flags: 0},
      {action: 'closeSoftKeyboard'},
    ]) {
      assert.equal((await call('ultron_act', {matcher, ...action})).isError, false);
      assert.deepEqual(requests.at(-1).body, {matcher, ...action});
    }
    const requestCount = requests.length;
    assert.equal((await call('ultron_act', {matcher, action: 'pressKey'})).isError, true);
    assert.equal(requests.length, requestCount);
    const recyclerArgs = {matcher, action: 'click', position: 0,
      childMatcher: {class: 'ViewMatchers', name: 'withText', args: ['Open']}, timeoutMs: 0};
    assert.equal((await call('ultron_recycler', recyclerArgs)).isError, false);
    assert.ok(requests.at(-1).path.endsWith('/ultron_recycler'));
    assert.deepEqual(requests.at(-1).body, recyclerArgs);
    const failed = await call('ultron_assert', {matcher, exists: false, timeoutMs: 0});
    assert.equal(failed.isError, true);
    assert.deepEqual(failed.structuredContent.snapshot, snapshot);
    for (const [named, expected] of [
      [{assertion: 'isNotEnabled'}, {condition: {name: 'not', args: [{class: 'ViewMatchers', name: 'isEnabled'}]}}],
      [{assertion: 'hasText', text: ''}, {condition: {class: 'ViewMatchers', name: 'withText', args: ['']}}],
      [{assertion: 'hasContentDescription', resourceId: 17039370}, {condition: {class: 'ViewMatchers', name: 'withContentDescription', args: [17039370]}}],
      [{assertion: 'isDisplayingAtLeast', percentage: 0}, {condition: {class: 'ViewMatchers', name: 'isDisplayingAtLeast', args: [0]}}],
      [{assertion: 'doesNotExist'}, {exists: false}],
    ]) {
      const result = await call('ultron_assert', {matcher, ...named, timeoutMs: 0});
      assert.equal(result.isError, true);
      assert.equal(result.structuredContent.operation.status, 'failure');
      assert.deepEqual(result.structuredContent.snapshot, snapshot);
      assert.deepEqual(requests.at(-1).body, {matcher, ...expected, timeoutMs: 0});
    }
    const assertionRequestCount = requests.length;
    assert.equal((await call('ultron_assert', {matcher, assertion: 'hasText'})).isError, true);
    assert.equal((await call('ultron_assert', {matcher, assertion: 'doesNotExist', exists: false})).isError, true);
    assert.equal(requests.length, assertionRequestCount);
    const composeMatcher = {name: 'hasTestTag', args: ['save']};
    const composeAction = {matcher: composeMatcher, action: 'scrollToKey', options: {keyType: 'long', key: '9223372036854775807'}, useUnmergedTree: false, timeoutMs: 0};
    assert.equal((await call('ultron_compose_act', composeAction)).isError, false);
    assert.ok(requests.at(-1).path.endsWith('/ultron_compose_act'));
    assert.deepEqual(requests.at(-1).body, composeAction);
    assert.equal(settings.driver, 'espresso');
    const composeCheck = {matcher: composeMatcher, assertion: 'textEquals', expected: ['', 'Saved'], options: {includeEditableText: false}};
    const composeFailure = await call('ultron_compose_assert', composeCheck);
    assert.equal(composeFailure.isError, true);
    assert.deepEqual(composeFailure.structuredContent.snapshot, snapshot);
    assert.ok(requests.at(-1).path.endsWith('/ultron_compose_assert'));
    assert.deepEqual(requests.at(-1).body, composeCheck);
    await call('ultron_settings', {driver: 'compose'});
    assert.equal(settings.driver, 'compose');
    const clicked = await call('ultron_element', {using: '-android viewtag', value: 'save', action: 'click', preflight: true});
    assert.deepEqual(clicked.structuredContent.snapshot, snapshot);
    assert.equal(clicked.structuredContent.preflight.status, 'unique');
    const findCalls = requests.filter(r => r.path.endsWith('/element')).length;
    const rejected = await call('ultron_element', {using: '-android viewtag', value: 'missing', action: 'click', preflight: true});
    assert.equal(rejected.structuredContent.operation.status, 'not_executed');
    assert.equal(requests.filter(r => r.path.endsWith('/element')).length, findCalls);
    assert.equal(requests.some(r => r.path === '/session/direct-session/element/compose-node/click'), true);
    const hidden = await call('ultron_element', {using: 'text', value: 'Save', action: 'displayed'});
    assert.equal(hidden.structuredContent.value, false);
    assert.equal(hidden.isError, false);
    assert.equal((await call('ultron_screenshot')).content[0].type, 'image');
    assert.equal((await call('ultron_back')).isError, false);
    assert.equal((await call('ultron_hide_keyboard')).isError, false);
    loseResponse = true;
    const before = requests.filter(r => r.path.endsWith('/ultron_act')).length;
    assert.equal((await call('ultron_act', {matcher, action: 'click'})).isError, true);
    assert.equal(requests.filter(r => r.path.endsWith('/ultron_act')).length, before + 1);
    assert.equal((await call('ultron_session', {action: 'close', sessionId: 'other'})).isError, true);
    const closed = await call('ultron_session', {action: 'close'});
    assert.equal(closed.structuredContent.restartInstrumentationRequired, true);
    assert.equal((await call('ultron_snapshot')).isError, true);
    await assert.rejects(new AndroidHttpClient(url).request('/unknown'), /Fixture rejected command/);
  } finally {
    await client.close();
    await transport.close();
    fixture.closeAllConnections();
    fixture.close();
  }
});

test('direct client only accepts HTTP URLs and requires an explicitly selected session', () => {
  assert.throws(() => new AndroidHttpClient('file:///tmp/server'), /http or https/);
  assert.throws(() => new AndroidHttpClient().path('/screenshot'), /session/);
});
