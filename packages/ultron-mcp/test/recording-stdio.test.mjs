import assert from 'node:assert/strict';
import {once} from 'node:events';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';

import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

test('standalone records UI tools, exports replayable JSON, blocks session replacement and preserves drafts on EOF', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'ultron-recording-stdio-'));
  let sessionId = null;
  const requests = [];
  const snapshot = {status: 'captured', roots: []};
  const fixture = createServer(async (req, res) => {
    let text = '';
    for await (const chunk of req) {
      text += chunk;
    }
    const body = text ? JSON.parse(text) : null;
    requests.push({path: req.url, body});
    let value;
    if (req.url === '/sessions') {
      value = sessionId ? [{id: sessionId}] : [];
    } else if (req.url === '/session') {
      sessionId = 'recorded-session';
      value = null;
    } else if (req.method === 'DELETE') {
      sessionId = null;
      value = null;
    } else if (req.url.endsWith('/appium/settings')) {
      value = {driver: 'espresso', ultronSnapshotEnabled: true};
    } else if (req.url.endsWith('/ultron_snapshot')) {
      value = snapshot;
    } else if (req.url.endsWith('/ultron_batch')) {
      value = {status: 'success', steps: body.steps.map((s) => ({id: s.id, status: 'success'}))};
    } else {
      value = {operation: {status: 'success'}, snapshot};
    }
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({sessionId, value}));
  });
  fixture.listen(0, '127.0.0.1');
  await once(fixture, 'listening');
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [
      fileURLToPath(new URL('../bin/ultron-mcp.mjs', import.meta.url)),
      '--url',
      `http://127.0.0.1:${fixture.address().port}`,
      '--recordings-dir',
      directory,
    ],
    stderr: 'pipe',
  });
  const client = new Client({name: 'recording-test', version: '1'});
  t.after(async () => {
    await client.close();
    fixture.closeAllConnections();
    await new Promise((resolve) => fixture.close(resolve));
    await rm(directory, {recursive: true, force: true});
  });
  await client.connect(transport);
  const call = (name, args = {}) => client.callTool({name, arguments: args});
  assert.equal((await call('ultron_recording_status')).structuredContent.active, false);
  assert.equal(requests.length, 0, 'Offline recording controls must not start Android');
  await call('ultron_session', {action: 'create', appPackage: 'test.app'});
  const started = (await call('ultron_recording_start', {name: 'Navigation'})).structuredContent;
  assert.equal((await call('ultron_session', {action: 'close'})).isError, true);
  assert.equal(
    requests.some((r) => r.path === '/session/recorded-session' && r.body === null),
    false,
  );
  await call('ultron_back');
  await call('ultron_hide_keyboard');
  await call('ultron_element', {using: 'text', value: 'Input', action: 'replaceText', text: 'Recorded'});
  await call('ultron_assert', {
    matcher: {class: 'ViewMatchers', name: 'withText', args: ['Recorded']},
    assertion: 'exists',
  });
  await call('ultron_snapshot');
  const finished = (await call('ultron_recording_finish', {outcome: 'passed'})).structuredContent;
  const scenario = JSON.parse(await readFile(finished.scenarioPath, 'utf8'));
  assert.equal(scenario.steps.length, 4);
  assert.equal(scenario.steps[2].params.backend, 'espresso');
  assert.equal((await call('ultron_batch', scenario)).isError, false);
  assert.deepEqual(requests.find((r) => r.path.endsWith('/ultron_batch')).body, scenario);
  const unfinished = (await call('ultron_recording_start', {name: 'Interrupted'})).structuredContent;
  await call('ultron_back');
  await client.close();
  const deadline = Date.now() + 3000;
  let draft;
  do {
    draft = JSON.parse(await readFile(unfinished.draftPath, 'utf8'));
    if (draft.state === 'interrupted') {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (Date.now() < deadline);
  assert.equal(draft.state, 'interrupted');
  assert.equal(JSON.parse(await readFile(started.draftPath, 'utf8')).state, 'recorded');
});
