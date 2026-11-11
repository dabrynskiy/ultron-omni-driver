import assert from 'node:assert/strict';
import {mkdtemp, readFile, rm, rename, mkdir, stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';

import UltronMcpPlugin from '../index.mjs';

const matcher = {class: 'ViewMatchers', name: 'withText', args: ['Go']};
const snapshot = {status: 'captured', roots: []};

async function setup(t) {
  const directory = await mkdtemp(join(tmpdir(), 'ultron-recording-test-'));
  t.after(() => rm(directory, {recursive: true, force: true}));
  const calls = [];
  const tools = new Map();
  const driver = {
    sessionId: 'session-a',
    async getSettings() {
      return {driver: 'compose', ultronSnapshotEnabled: true};
    },
    async executeScript(command, [args]) {
      calls.push({command, args});
      if (command.endsWith('ultronSnapshot')) {
        return snapshot;
      }
      if (command.endsWith('ultronBatch')) {
        return {status: 'success', steps: args.steps.map((s) => ({id: s.id, status: 'success'}))};
      }
      return {operation: {status: 'success'}, snapshot, value: args.action === 'displayed' ? false : null};
    },
  };
  const core = {
    getDriver() {
      return driver;
    },
  };
  const plugin = new UltronMcpPlugin({recordingsDir: directory});
  plugin.register(
    {
      addTool(tool) {
        tools.set(tool.name, tool);
      },
    },
    core,
  );
  return {directory, driver, calls, plugin, core, call: (name, args = {}) => tools.get(name).execute(args)};
}

test('records normalized driver commands, navigation and explicit element backend, excluding observations', async (t) => {
  const f = await setup(t);
  const started = await f.call('ultron_recording_start', {name: 'Checkout'});
  assert.equal(started.isError, false);
  const recording = started.structuredContent;
  await f.call('ultron_act', {sessionId: 'session-a', matcher, action: 'replaceText', text: '', timeoutMs: 0});
  await f.call('ultron_snapshot');
  await f.call('ultron_back');
  await f.call('ultron_hide_keyboard');
  await f.call('ultron_element', {using: '-android viewtag', value: 'input', action: 'clear'});
  const read = await f.call('ultron_element', {using: '-android viewtag', value: 'input', action: 'displayed'});
  assert.equal(read.structuredContent.value, false);
  assert.equal(read.isError, false);
  await f.call('ultron_assert', {matcher, assertion: 'isDisplayed', timeoutMs: 0});
  assert.equal((await f.call('ultron_recording_status')).structuredContent.stepCount, 6);
  const draft = JSON.parse(await readFile(recording.draftPath, 'utf8'));
  assert.equal(draft.scenario.steps.length, 6, 'Draft is updated after every operation');
  const finished = (await f.call('ultron_recording_finish', {outcome: 'passed'})).structuredContent;
  assert.equal(finished.verification, 'not_replayed');
  const scenario = JSON.parse(await readFile(finished.scenarioPath, 'utf8'));
  assert.deepEqual(Object.keys(scenario).sort(), ['schemaVersion', 'steps']);
  assert.deepEqual(
    scenario.steps.map((s) => s.command),
    ['ultronAct', 'ultronBack', 'ultronHideKeyboard', 'ultronElement', 'ultronElement', 'ultronAssert'],
  );
  assert.deepEqual(scenario.steps[0].params, {matcher, action: 'replaceText', text: '', timeoutMs: 0});
  assert.deepEqual(scenario.steps[3].params, {
    using: '-android viewtag',
    value: 'input',
    action: 'clear',
    backend: 'compose',
  });
  assert.equal(scenario.steps[4].params.action, 'displayed');
  assert.deepEqual(scenario.steps[5].params, {
    matcher,
    timeoutMs: 0,
    condition: {class: 'ViewMatchers', name: 'isDisplayed'},
  });
  const journal = (await readFile(finished.journalPath, 'utf8')).trim().split('\n').map(JSON.parse);
  assert.equal(journal.filter((e) => e.type === 'command').length, 6);
  assert.ok(journal.some((e) => e.type === 'tool' && e.tool === 'ultron_snapshot'));
  assert.equal((await stat(finished.scenarioPath)).mode & 0o777, 0o600);
  assert.equal(
    (await f.call('ultron_batch', scenario)).isError,
    false,
    'Export replays through the existing batch tool',
  );
});

for (const status of ['failure', 'unknown']) {
  test(`does not silently export ${status} operations as a passing scenario`, async (t) => {
    const f = await setup(t);
    await f.call('ultron_recording_start', {name: status});
    f.driver.executeScript = async () => {
      if (status === 'unknown') {
        throw new Error('Connection lost after sending action');
      }
      return {operation: {status: 'failure', error: 'not found'}, snapshot};
    };
    assert.equal((await f.call('ultron_act', {matcher, action: 'click'})).isError, true);
    const progress = (await f.call('ultron_recording_status')).structuredContent;
    assert.deepEqual(progress.issues[0].statuses, [status]);
    assert.equal((await f.call('ultron_recording_finish', {outcome: 'passed'})).isError, true);
    const failed = (await f.call('ultron_recording_finish', {outcome: 'failed'})).structuredContent;
    assert.equal(failed.scenarioPath, null);
    assert.equal(JSON.parse(await readFile(failed.draftPath, 'utf8')).state, 'failed');
  });
}

test('writes pending intent before execution and serializes finish behind in-flight operations', async (t) => {
  const f = await setup(t);
  const started = (await f.call('ultron_recording_start', {name: 'Queued'})).structuredContent;
  let release;
  let entered;
  const executionStarted = new Promise((resolve) => {
    entered = resolve;
  });
  f.driver.executeScript = async () => {
    const draft = JSON.parse(await readFile(started.draftPath, 'utf8'));
    assert.equal(draft.events.length, 1);
    assert.equal(draft.events[0].statuses, undefined);
    entered();
    await new Promise((resolve) => {
      release = resolve;
    });
    return {operation: {status: 'success'}, snapshot};
  };
  const action = f.call('ultron_act', {matcher, action: 'click'});
  await executionStarted;
  const finish = f.call('ultron_recording_finish', {outcome: 'passed'});
  release();
  assert.equal((await action).isError, false);
  assert.equal((await finish).structuredContent.stepCount, 1);
});

test('pins the session, allows offline status/finish and never starts another device for controls', async (t) => {
  const f = await setup(t);
  await f.call('ultron_recording_start', {name: 'Pinned'});
  const selected = f.core.getDriver;
  f.core.getDriver = () => ({...f.driver, sessionId: 'other'});
  assert.equal((await f.call('ultron_back')).isError, true);
  assert.equal(f.calls.length, 0);
  f.core.getDriver = selected;
  await f.call('ultron_hide_keyboard');
  f.core.getDriver = () => null;
  assert.equal((await f.call('ultron_recording_status')).structuredContent.stepCount, 1);
  assert.equal((await f.call('ultron_recording_finish', {outcome: 'passed'})).isError, false);
});

test('batch recording flattens successful steps without replaying the batch a second time', async (t) => {
  const f = await setup(t);
  await f.call('ultron_recording_start', {name: 'Batch'});
  const steps = [
    {id: 'back', command: 'ultronBack', params: {}},
    {id: 'hide', command: 'ultronHideKeyboard', params: {}},
  ];
  assert.equal((await f.call('ultron_batch', {steps})).isError, false);
  const finished = (await f.call('ultron_recording_finish', {outcome: 'passed'})).structuredContent;
  assert.deepEqual(
    JSON.parse(await readFile(finished.scenarioPath, 'utf8')).steps.map((s) => s.command),
    ['ultronBack', 'ultronHideKeyboard'],
  );
  assert.equal(f.calls.length, 1);
});

test('malformed batch response cannot turn skipped or mismatched steps into a passing export', async (t) => {
  const f = await setup(t);
  await f.call('ultron_recording_start', {name: 'Malformed response'});
  f.driver.executeScript = async () => ({status: 'success', steps: [{id: 'back', status: 'skipped'}]});
  const output = await f.call('ultron_batch', {steps: [{id: 'back', command: 'ultronBack', params: {}}]});
  assert.equal(output.isError, true);
  assert.deepEqual((await f.call('ultron_recording_status')).structuredContent.issues[0].statuses, ['unknown']);
  assert.equal((await f.call('ultron_recording_finish', {outcome: 'passed'})).isError, true);
});

test('recorded element preflight retains the uniqueness guard for replay', async (t) => {
  const f = await setup(t);
  const execute = f.driver.executeScript;
  f.driver.executeScript = async (command, args) =>
    command.endsWith('ultronSnapshot')
      ? {
          status: 'captured',
          truncated: false,
          errors: [],
          roots: [{type: 'View', tag: 'target', tag_is_stringified: false}],
        }
      : execute.call(f.driver, command, args);
  await f.call('ultron_recording_start', {name: 'Unique element'});
  const output = await f.call('ultron_element', {
    backend: 'espresso',
    using: '-android viewtag',
    value: 'target',
    action: 'click',
    preflight: true,
  });
  assert.equal(output.isError, false, JSON.stringify(output));
  const finished = (await f.call('ultron_recording_finish', {outcome: 'passed'})).structuredContent;
  assert.equal(finished.scenario.steps[0].params.requireUnique, true);
  assert.equal(finished.scenario.steps[0].params.preflight, undefined);
});

test('intent persistence failure prevents mutation and result persistence failure preserves device success', async (t) => {
  for (const before of [true, false]) {
    const f = await setup(t);
    const started = (await f.call('ultron_recording_start', {name: 'Disk error'})).structuredContent;
    const journal = started.journalPath;
    const breakJournal = async () => {
      await rename(journal, journal + '.saved');
      await mkdir(journal);
    };
    let calls = 0;
    if (before) {
      await breakJournal();
    }
    f.driver.executeScript = async () => {
      calls++;
      await breakJournal();
      return {operation: {status: 'success'}, snapshot};
    };
    const output = await f.call('ultron_back');
    assert.equal(calls, before ? 0 : 1);
    if (before) {
      assert.equal(output.isError, true);
    } else {
      assert.equal(output.structuredContent.operation.status, 'success');
      assert.equal(output.structuredContent.recording.status, 'persistence_error');
    }
    assert.equal((await f.call('ultron_recording_finish', {outcome: 'passed'})).isError, true);
  }
});

test('discard keeps artifacts and interrupted drafts cannot be exported as passed', async (t) => {
  const f = await setup(t);
  const started = (await f.call('ultron_recording_start', {name: 'Interrupted'})).structuredContent;
  await f.call('ultron_back');
  await f.plugin.recorder.interrupt();
  assert.equal(JSON.parse(await readFile(started.draftPath, 'utf8')).state, 'interrupted');
  assert.equal((await f.call('ultron_recording_finish', {outcome: 'passed'})).isError, true);
  const discarded = (await f.call('ultron_recording_discard')).structuredContent;
  assert.equal(discarded.state, 'discarded');
  assert.equal(JSON.parse(await readFile(started.draftPath, 'utf8')).state, 'discarded');
});
