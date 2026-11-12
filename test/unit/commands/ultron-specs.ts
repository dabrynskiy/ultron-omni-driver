import assert from 'node:assert/strict';
import {afterEach, beforeEach, describe, it} from 'node:test';

import sinon from 'sinon';

import {EspressoDriver} from '../../../lib/driver.js';

describe('Ultron LLM execute commands', function () {
  const sandbox = sinon.createSandbox();
  let driver: EspressoDriver;
  let command: sinon.SinonStub;
  const matcher = {class: 'LlmViewMatchers', name: 'propertyEquals', args: ['text', 'String']};

  beforeEach(function () {
    driver = new EspressoDriver({} as any, false);
    command = sandbox.stub().resolves({operation: {status: 'success'}, snapshot: {roots: []}});
    (driver as any).espresso = {jwproxy: {command}};
  });
  afterEach(function () {
    sandbox.restore();
  });

  it('passes nested matchers and returns the structured result from the device', async function () {
    const result = await (driver as any).execute('mobile: ultronAct', [
      {matcher, action: 'replaceText', text: '', timeoutMs: 0},
    ]);
    assert.deepEqual(result, {operation: {status: 'success'}, snapshot: {roots: []}});
    assert.deepEqual(command.firstCall.args, [
      '/appium/execute_mobile/ultron_act',
      'POST',
      {matcher, action: 'replaceText', text: '', timeoutMs: 0},
    ]);
  });

  it('preserves false existence checks and zero timeouts', async function () {
    await (driver as any).execute('mobile: ultronAssert', [{matcher, exists: false, timeoutMs: 0}]);
    assert.deepEqual(command.firstCall.args, [
      '/appium/execute_mobile/ultron_assert',
      'POST',
      {matcher, condition: undefined, exists: false, timeoutMs: 0},
    ]);
  });

  it('routes mixed single commands with all optional parameters and version 2 batches', async function () {
    const examples = [
      [
        'ultronAutomatorAct',
        'ultron_automator_act',
        {strategy: 'text', locator: 'Go', action: 'replaceText', text: '', packageName: 'app', index: 0, timeoutMs: 0},
      ],
      [
        'ultronAutomatorAssert',
        'ultron_automator_assert',
        {
          strategy: 'text',
          locator: 'Go',
          assertion: 'enabled',
          expected: false,
          packageName: 'app',
          index: 0,
          timeoutMs: 0,
        },
      ],
      [
        'ultronWebAct',
        'ultron_web_act',
        {
          webViewMatcher: matcher,
          locator: {using: 'ID', value: 'input'},
          action: 'typeText',
          text: '',
          frames: [{index: 0}],
          timeoutMs: 0,
        },
      ],
      [
        'ultronWebAssert',
        'ultron_web_assert',
        {
          webViewMatcher: matcher,
          locator: {using: 'ID', value: 'status'},
          assertion: 'textEquals',
          text: '',
          frames: [{idOrName: 'inner'}],
          timeoutMs: 0,
        },
      ],
    ] as const;
    for (const [name, route, params] of examples) {
      await (driver as any).execute(`mobile: ${name}`, [params]);
      assert.deepEqual(command.lastCall.args, [`/appium/execute_mobile/${route}`, 'POST', params]);
    }
    const steps = examples.map(([name, , params], i) => ({id: String(i), command: name, params}));
    await (driver as any).execute('mobile: ultronBatch', [{schemaVersion: 2, steps}]);
    assert.deepEqual(command.lastCall.args, ['/appium/execute_mobile/ultron_batch', 'POST', {schemaVersion: 2, steps}]);
  });

  it('forwards positional clicks and action options through the execute method map', async function () {
    await (driver as any).execute('mobile: ultronAct', [
      {
        matcher,
        action: 'clickTopRight',
        offsetX: -8,
        offsetY: 0,
        autoScroll: false,
        name: 'Continue',
        timeoutMs: 0,
      },
    ]);
    assert.deepEqual(command.firstCall.args, [
      '/appium/execute_mobile/ultron_act',
      'POST',
      {
        matcher,
        action: 'clickTopRight',
        text: undefined,
        timeoutMs: 0,
        offsetX: -8,
        offsetY: 0,
        autoScroll: false,
        name: 'Continue',
      },
    ]);
    await (driver as any).execute('mobile: ultronAct', [
      {matcher, action: 'pressKey', keyCode: 29, metaState: 1, flags: 0},
    ]);
    assert.deepEqual(command.secondCall.args, [
      '/appium/execute_mobile/ultron_act',
      'POST',
      {
        matcher,
        action: 'pressKey',
        text: undefined,
        timeoutMs: undefined,
        keyCode: 29,
        metaState: 1,
        flags: 0,
      },
    ]);
  });

  it('exposes snapshot bounds and the last listener result', async function () {
    await (driver as any).execute('mobile: ultronSnapshot', [{maxDepth: 0, maxNodes: 2, useUnmergedTree: false}]);
    assert.deepEqual(command.firstCall.args, [
      '/appium/execute_mobile/ultron_snapshot',
      'POST',
      {maxDepth: 0, maxNodes: 2, useUnmergedTree: false},
    ]);
    command.resolves(null);
    assert.equal(await (driver as any).execute('mobile: ultronLastResult', []), null);
    assert.deepEqual(command.secondCall.args, ['/appium/execute_mobile/ultron_last_result', 'GET']);
  });

  it('forwards snapshot mode without changing the default request and validates session settings', async function () {
    await (driver as any).execute('mobile: ultronSnapshot', [{mode: 'automator', maxNodes: 1}]);
    assert.deepEqual(command.firstCall.args, [
      '/appium/execute_mobile/ultron_snapshot',
      'POST',
      {mode: 'automator', maxNodes: 1},
    ]);
    command.resetHistory();
    await (driver as any).execute('mobile: ultronSnapshot', [{}]);
    assert.deepEqual(command.firstCall.args, ['/appium/execute_mobile/ultron_snapshot', 'POST', {}]);
    command.resetHistory();
    await assert.rejects(driver.updateSettings({ultronSnapshotMode: 'dom'}));
    assert.equal(command.called, false);
    await driver.updateSettings({ultronSnapshotMode: 'automator'});
    assert.equal(driver.settings.getSettings().ultronSnapshotMode, 'automator');
    command.rejects(new Error('device disconnected'));
    await assert.rejects(driver.updateSettings({ultronSnapshotMode: 'ultron'}), /device disconnected/);
    assert.equal(driver.settings.getSettings().ultronSnapshotMode, 'automator');
  });

  it('routes RecyclerView targets and preserves zero positions, counts and empty text', async function () {
    const args = {matcher, action: 'replaceText', position: 0, childMatcher: matcher, text: '', timeoutMs: 0};
    await (driver as any).execute('mobile: ultronRecycler', [args]);
    assert.deepEqual(command.firstCall.args, ['/appium/execute_mobile/ultron_recycler', 'POST', args]);
    await (driver as any).execute('mobile: ultronRecycler', [{matcher, action: 'assertItemCount', itemCount: 0}]);
    assert.deepEqual(command.secondCall.args, [
      '/appium/execute_mobile/ultron_recycler',
      'POST',
      {matcher, action: 'assertItemCount', itemCount: 0},
    ]);
    const searched = {matcher, action: 'scrollToItem', itemMatcher: matcher, searchLimit: 7};
    await (driver as any).execute('mobile: ultronRecycler', [searched]);
    assert.deepEqual(command.thirdCall.args, ['/appium/execute_mobile/ultron_recycler', 'POST', searched]);
  });

  it('rejects actions without a matcher before sending them to the device', async function () {
    await assert.rejects((driver as any).execute('mobile: ultronAct', [{action: 'click'}]));
    assert.equal(command.called, false);
  });

  it('sends mixed batches as one device request and preserves failure evidence and skipped steps', async function () {
    const steps = [
      {id: 'native', command: 'ultronAct', params: {matcher, action: 'replaceText', text: '', timeoutMs: 0}},
      {id: 'compose', command: 'ultronComposeAssert', params: {matcher: {name: 'hasTestTag', args: ['title']}}},
    ];
    const failed = {
      status: 'failure',
      failedStepId: 'native',
      steps: [
        {id: 'native', status: 'failure', snapshot: {status: 'captured', roots: []}},
        {id: 'compose', status: 'skipped', snapshot: null},
      ],
    };
    command.resolves(failed);
    assert.deepEqual(await (driver as any).execute('mobile: ultronBatch', [{steps}]), failed);
    assert.deepEqual(command.args, [['/appium/execute_mobile/ultron_batch', 'POST', {schemaVersion: 1, steps}]]);
  });

  it('routes replayable navigation and explicit-backend locator operations', async function () {
    await (driver as any).execute('mobile: ultronBack', []);
    await (driver as any).execute('mobile: ultronHideKeyboard', []);
    await (driver as any).execute('mobile: ultronElement', [
      {backend: 'compose', using: '-android viewtag', value: 'input', action: 'replaceText', text: ''},
    ]);
    assert.deepEqual(command.args, [
      ['/appium/execute_mobile/ultron_back', 'POST', {}],
      ['/appium/execute_mobile/ultron_hide_keyboard', 'POST', {}],
      [
        '/appium/execute_mobile/ultron_element',
        'POST',
        {backend: 'compose', using: '-android viewtag', value: 'input', action: 'replaceText', text: ''},
      ],
    ]);
  });

  it('routes Compose operations independently of the global driver and preserves typed options', async function () {
    const target = {name: 'hasTestTag', args: ['feed']};
    const args = {
      matcher: target,
      action: 'scrollToKey',
      options: {keyType: 'long', key: '9223372036854775807'},
      useUnmergedTree: false,
      timeoutMs: 0,
    };
    await (driver as any).execute('mobile: ultronComposeAct', [args]);
    assert.deepEqual(command.firstCall.args, ['/appium/execute_mobile/ultron_compose_act', 'POST', args]);
    const check = {
      matcher: target,
      assertion: 'textEquals',
      expected: ['', 'Saved'],
      options: {includeEditableText: false},
    };
    await (driver as any).execute('mobile: ultronComposeAssert', [check]);
    assert.deepEqual(command.secondCall.args, ['/appium/execute_mobile/ultron_compose_assert', 'POST', check]);
    await (driver as any).execute('mobile: ultronComposeAssert', [{matcher: target}]);
    assert.deepEqual(command.thirdCall.args, [
      '/appium/execute_mobile/ultron_compose_assert',
      'POST',
      {matcher: target},
    ]);
  });

  it('applies the snapshot setting on the device and reports its current value', async function () {
    command.resolves(null);
    await driver.updateSettings({ultronSnapshotEnabled: true});
    assert.deepEqual(command.firstCall.args, ['/appium/settings', 'POST', {settings: {ultronSnapshotEnabled: true}}]);
    assert.equal(driver.settings.getSettings().ultronSnapshotEnabled, true);
    command.resolves({ultronSnapshotEnabled: false});
    assert.equal((await driver.getSettings()).ultronSnapshotEnabled, false);
  });

  it('rejects non-boolean snapshot settings before sending them to the device', async function () {
    await assert.rejects(driver.updateSettings({ultronSnapshotEnabled: 'true'}));
    assert.equal(command.called, false);
  });

  it('propagates device errors without committing the local setting', async function () {
    command.rejects(new Error('device disconnected'));
    await assert.rejects(driver.updateSettings({ultronSnapshotEnabled: true}), /device disconnected/);
    assert.equal(driver.settings.getSettings().ultronSnapshotEnabled, undefined);
  });
});
