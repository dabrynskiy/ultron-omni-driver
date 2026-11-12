import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';

import {z} from 'zod';

import {AndroidHttpClient} from '../direct-client.mjs';
import UltronMcpPlugin from '../index.mjs';

const webViewMatcher = {class: 'ViewMatchers', name: 'withId', args: [123]};
const examples = [
  [
    'ultron_automator_act',
    'ultronAutomatorAct',
    {strategy: 'text', locator: 'Go', action: 'click', index: 0, timeoutMs: 0},
  ],
  [
    'ultron_automator_assert',
    'ultronAutomatorAssert',
    {strategy: 'text', locator: 'Go', assertion: 'enabled', expected: false, timeoutMs: 0},
  ],
  [
    'ultron_web_act',
    'ultronWebAct',
    {
      webViewMatcher,
      locator: {using: 'ID', value: 'input'},
      action: 'typeText',
      text: '',
      frames: [{index: 0}],
      timeoutMs: 0,
    },
  ],
  [
    'ultron_web_assert',
    'ultronWebAssert',
    {
      webViewMatcher,
      locator: {using: 'ID', value: 'title'},
      assertion: 'textEquals',
      text: '',
      frames: [{idOrName: 'content'}],
      timeoutMs: 0,
    },
  ],
];

function setup(options = {}) {
  const calls = [];
  const tools = new Map();
  const driver = {
    sessionId: 'mixed-session',
    async getSettings() {
      return {ultronSnapshotEnabled: true};
    },
    async executeScript(command, [args]) {
      calls.push({command, args});
      if (options.execute) {
        return options.execute(command, args);
      }
      return command === 'mobile: ultronBatch'
        ? {
            schemaVersion: args.schemaVersion,
            status: 'success',
            failedStepId: null,
            steps: args.steps.map(({id}) => ({id, status: 'success', snapshot: null})),
          }
        : {operation: {status: 'success'}, snapshot: {status: 'captured', roots: []}, value: false};
    },
  };
  new UltronMcpPlugin(options).register(
    {
      addTool(tool) {
        tools.set(tool.name, tool);
      },
    },
    {
      getDriver() {
        return driver;
      },
    },
  );
  return {tools, calls};
}

test('mixed single tools expose schemas and preserve zero, false, empty text and frame context', async () => {
  const {tools, calls} = setup();
  for (const [tool, command, args] of examples) {
    z.toJSONSchema(tools.get(tool).parameters);
    const output = await tools.get(tool).execute(args);
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.equal(output.structuredContent.value, false);
    assert.deepEqual(calls.at(-1), {command: `mobile: ${command}`, args});
  }
});

test('version 2 mixed batch is one request; new commands require explicit version 2', async () => {
  const {tools, calls} = setup();
  const steps = examples.map(([, command, params], i) => ({id: String(i), command, params}));
  const tool = tools.get('ultron_batch');
  assert.equal((await tool.execute({schemaVersion: 2, steps})).isError, false);
  assert.deepEqual(calls, [{command: 'mobile: ultronBatch', args: {schemaVersion: 2, steps}}]);
  for (const schemaVersion of [undefined, 1]) {
    assert.equal((await tool.execute({schemaVersion, steps})).isError, true);
  }
  assert.equal(calls.length, 1);
});

test('invalid mixed operations reject before dispatch, also in a later batch step', async () => {
  const {tools, calls} = setup();
  const invalid = [
    ['ultron_automator_act', {strategy: 'text', locator: 'Go', action: 'replaceText'}],
    ['ultron_automator_assert', {strategy: 'text', locator: 'Go', assertion: 'exists', expected: false}],
    ['ultron_automator_act', {strategy: 'text', locator: 'Go', action: 'click', timeoutMs: '0'}],
    [
      'ultron_web_act',
      {webViewMatcher, locator: {using: 'ID', value: 'go'}, action: 'click', frames: [{index: 0, idOrName: 'both'}]},
    ],
    ['ultron_web_assert', {webViewMatcher, locator: {using: 'ID', value: 'title'}, assertion: 'textEquals'}],
    [
      'ultron_web_act',
      {webViewMatcher: {...webViewMatcher, scope: {}}, locator: {using: 'ID', value: 'go'}, action: 'click'},
    ],
  ];
  for (const [name, params] of invalid) {
    assert.equal((await tools.get(name).execute(params)).isError, true);
    const command = examples.find(([tool]) => name === tool)[1];
    const steps = [
      {id: 'first', command: 'ultronBack', params: {}},
      {id: 'invalid', command, params},
    ];
    assert.equal((await tools.get('ultron_batch').execute({schemaVersion: 2, steps})).isError, true);
  }
  assert.equal(calls.length, 0);
});

test('mixed failures retain executor diagnostics and never retry a lost mutation response', async () => {
  const value = {
    operation: {status: 'failure', backend: 'web', error: 'expected text'},
    snapshot: {status: 'partial', context: {backend: 'web', frames: [{index: 0}]}, roots: []},
  };
  const {tools} = setup({execute: () => value});
  const failed = await tools.get('ultron_web_assert').execute(examples[3][2]);
  assert.equal(failed.isError, true);
  assert.deepEqual(failed.structuredContent, value);
  const lost = setup({
    execute: () => {
      throw new Error('connection lost');
    },
  });
  assert.equal((await lost.tools.get('ultron_automator_act').execute(examples[0][2])).isError, true);
  assert.equal(lost.calls.length, 1);
});

test('recording preserves version 2 for mixed singles and explicit version 2 batches', async (t) => {
  const recordingsDir = await mkdtemp(join(tmpdir(), 'mixed-recording-'));
  t.after(() => rm(recordingsDir, {recursive: true, force: true}));
  const {tools} = setup({recordingsDir});
  for (const batch of [false, true]) {
    assert.equal((await tools.get('ultron_recording_start').execute({name: 'Mixed'})).isError, false);
    if (batch) {
      await tools
        .get('ultron_batch')
        .execute({schemaVersion: 2, steps: [{id: 'legacy', command: 'ultronBack', params: {}}]});
    } else {
      for (const [tool, , args] of examples) {
        assert.equal((await tools.get(tool).execute(args)).isError, false);
      }
    }
    const result = (await tools.get('ultron_recording_finish').execute({outcome: 'passed'})).structuredContent;
    assert.equal(result.scenario.schemaVersion, 2);
    assert.deepEqual(JSON.parse(await readFile(result.scenarioPath, 'utf8')), result.scenario);
    assert.equal((await tools.get('ultron_batch').execute(result.scenario)).isError, false);
  }
});

test('direct HTTP routes all mixed singles without Appium or extra requests', async (t) => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({url, body: JSON.parse(options.body)});
    return Response.json({value: {operation: {status: 'success'}}});
  });
  const client = new AndroidHttpClient('http://localhost:8300');
  client.sessionId = 'mixed';
  for (const [name, command, args] of examples) {
    await client.executeScript(`mobile: ${command}`, [args]);
    assert.deepEqual(calls.at(-1), {
      url: `http://localhost:8300/session/mixed/appium/execute_mobile/${name}`,
      body: args,
    });
  }
  assert.equal(calls.length, 4);
});
