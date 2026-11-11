import assert from 'node:assert/strict';
import {test} from 'node:test';

import {z} from 'zod';

import {AndroidHttpClient} from '../direct-client.mjs';
import UltronMcpPlugin from '../index.mjs';

const steps = [
  {
    id: 'native',
    command: 'ultronAct',
    params: {matcher: {class: 'ViewMatchers', name: 'withText', args: ['Go']}, action: 'click', timeoutMs: 0},
  },
  {
    id: 'compose',
    command: 'ultronComposeAssert',
    params: {matcher: {name: 'hasTestTag', args: ['title']}, assertion: 'exists'},
  },
];

function setup(executeScript) {
  const tools = new Map();
  const driver = {
    executeScript,
    getSettings() {
      throw new Error('Batch must not read snapshot settings');
    },
    updateSettings() {
      throw new Error('Batch must not change snapshot settings');
    },
  };
  new UltronMcpPlugin().register(
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
  return tools.get('ultron_batch');
}

test('batch MCP preserves raw driver params, sends one request and does not change settings', async () => {
  const calls = [];
  const value = {
    status: 'success',
    steps: steps.map((s) => ({id: s.id, status: 'success', snapshot: null})),
    failedStepId: null,
  };
  const tool = setup(async (...args) => {
    calls.push(args);
    return value;
  });
  const schema = z.toJSONSchema(tool.parameters);
  assert.equal(schema.properties.steps.maxItems, 100);
  const output = await tool.execute({sessionId: 'chosen', schemaVersion: 1, steps});
  assert.equal(output.isError, false);
  assert.deepEqual(output.structuredContent, value);
  assert.deepEqual(calls, [['mobile: ultronBatch', [{schemaVersion: 1, steps}]]]);
});

test('batch MCP retains the failed snapshot and skipped steps and never retries transport loss', async () => {
  const value = {
    status: 'failure',
    failedStepId: 'native',
    steps: [
      {id: 'native', status: 'failure', snapshot: {status: 'captured', roots: []}},
      {id: 'compose', status: 'skipped', snapshot: null},
    ],
  };
  const output = await setup(async () => value).execute({steps});
  assert.equal(output.isError, true);
  assert.deepEqual(output.structuredContent, value);
  let calls = 0;
  const lost = await setup(async () => {
    calls++;
    throw new Error('connection lost');
  }).execute({steps});
  assert.equal(lost.isError, true);
  assert.equal(calls, 1);
});

test('batch MCP rejects malformed envelopes before contacting the driver', async () => {
  let calls = 0;
  const tool = setup(async () => {
    calls++;
  });
  for (const args of [
    {steps: []},
    {schemaVersion: 2, steps},
    {steps: [steps[0], steps[0]]},
    {steps: [{...steps[0], id: ' '}]},
    {steps: [{...steps[0], command: 'ultronSnapshot'}]},
    {steps: [{...steps[0], params: null}]},
    {steps: [{...steps[0], onFailure: 'continue'}]},
    {steps: Array.from({length: 101}, (_, i) => ({...steps[0], id: String(i)}))},
  ]) {
    assert.equal((await tool.execute(args)).isError, true);
  }
  assert.equal(calls, 0);
});

test('direct batch routing keeps it as a single Android HTTP request', async (t) => {
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({url, options});
    return Response.json({value: {status: 'success', steps: []}});
  });
  const driver = new AndroidHttpClient('http://localhost:8300');
  driver.sessionId = 'batch-session';
  await driver.executeScript('mobile: ultronBatch', [{steps}]);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'http://localhost:8300/session/batch-session/appium/execute_mobile/ultron_batch');
  assert.deepEqual(JSON.parse(calls[0].options.body), {steps});
});
