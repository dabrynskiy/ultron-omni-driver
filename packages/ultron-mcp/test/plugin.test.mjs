import assert from 'node:assert/strict';
import {test} from 'node:test';

import {z} from 'zod';

import UltronMcpPlugin from '../index.mjs';

const matcher = {class: 'LlmViewMatchers', name: 'propertyEquals', args: ['text', 'Continue']};
const snapshot = {status: 'captured', roots: [{type: 'View', children: [{type: 'SemanticsNode', node_id: 7}]}]};
const operation = {operation: {status: 'success'}, snapshot};

function setup() {
  const tools = new Map();
  const calls = [];
  let settings = {ultronSnapshotEnabled: false, driver: 'espresso'};
  const driver = {
    async getSettings() {
      calls.push(['getSettings']);
      return {...settings};
    },
    async updateSettings(value) {
      calls.push(['updateSettings', value]);
      settings = {...settings, ...value};
    },
    async executeScript(script, args) {
      calls.push([script, args]);
      if (script.endsWith('ultronSnapshot')) {
        return snapshot;
      }
      if (script.endsWith('ultronLastResult')) {
        return null;
      }
      return operation;
    },
  };
  const selected = [];
  const core = {
    getDriver(id) {
      selected.push(id);
      return id === 'missing' ? null : driver;
    },
  };
  new UltronMcpPlugin().register(
    {
      addTool(tool) {
        tools.set(tool.name, tool);
      },
    },
    core,
  );
  return {tools, driver, calls, selected, core};
}

test('registers discoverable JSON schemas and preserves mixed snapshots', async () => {
  const {tools, calls, selected} = setup();
  assert.equal(tools.size, 21);
  for (const tool of tools.values()) {
    assert.equal(z.toJSONSchema(tool.parameters).type, 'object');
  }
  const output = await tools.get('ultron_snapshot').execute({sessionId: 'chosen', maxDepth: 0, useUnmergedTree: false});
  assert.deepEqual(selected, ['chosen']);
  assert.deepEqual(calls, [['mobile: ultronSnapshot', [{maxDepth: 0, useUnmergedTree: false}]]]);
  assert.deepEqual(output.structuredContent, snapshot);
  assert.deepEqual(JSON.parse(output.content[0].text), snapshot);
  assert.equal(output.isError, false);
});

test('enables snapshots, preserves empty text/zero timeout, and sends only driver arguments', async () => {
  const {tools, calls} = setup();
  const output = await tools
    .get('ultron_act')
    .execute({sessionId: 'chosen', matcher, action: 'replaceText', text: '', timeoutMs: 0});
  assert.equal(output.isError, false);
  assert.deepEqual(calls, [
    ['getSettings'],
    ['updateSettings', {ultronSnapshotEnabled: true}],
    ['mobile: ultronAct', [{matcher, action: 'replaceText', text: '', timeoutMs: 0}]],
  ]);
});

test('one snapshot tool forwards explicit observation modes and rejects DOM overrides', async () => {
  const {tools, driver, calls} = setup();
  const accessibility = {
    ...snapshot,
    backend: 'automator',
    mode: 'automator',
    roots: [{type: 'AccessibilityNode', source: 'accessibility'}],
  };
  driver.executeScript = async (script, args) => {
    calls.push([script, args]);
    return accessibility;
  };
  const output = await tools.get('ultron_snapshot').execute({mode: 'automator', maxDepth: 0, maxNodes: 1});
  assert.equal(output.isError, false);
  assert.deepEqual(output.structuredContent, accessibility);
  assert.deepEqual(calls, [['mobile: ultronSnapshot', [{mode: 'automator', maxDepth: 0, maxNodes: 1}]]]);
  for (const args of [{mode: 'auto'}, {mode: 'dom'}, {includeDom: false}]) {
    assert.equal((await tools.get('ultron_snapshot').execute(args)).isError, true);
  }
  assert.equal(calls.length, 1);
  assert.equal((await tools.get('ultron_settings').execute({snapshotMode: 'automator'})).isError, false);
  assert.deepEqual(calls[1], ['updateSettings', {ultronSnapshotMode: 'automator'}]);
});

test('assertion failures are MCP errors retaining the operation and UI evidence', async () => {
  const {tools, driver} = setup();
  const failed = {...operation, operation: {status: 'failure', error: 'Expected text missing'}};
  driver.executeScript = async () => failed;
  const output = await tools.get('ultron_assert').execute({matcher, exists: false, timeoutMs: 0});
  assert.equal(output.isError, true);
  assert.deepEqual(output.structuredContent, failed);
});

test('matcher tool checks accessibility/DOM offline and fresh with source-specific schemas and modes', async () => {
  const {tools, driver, calls, selected} = setup();
  const tool = tools.get('ultron_check_matcher');
  const ui = {
    status: 'captured',
    truncated: false,
    errors: [],
    backend: 'automator',
    roots: [
      {
        type: 'AccessibilityNode',
        text: 'Save',
        window: {id: 7},
        dom_status: 'captured',
        children: [
          {
            type: 'DomNode',
            tagName: 'button',
            selector: 'button',
            context: [],
            attributes: {id: 'save'},
          },
        ],
      },
    ],
  };
  const original = structuredClone(ui);
  const query = {backend: 'automator', using: 'text', value: 'Save', scope: {windowId: 7}};
  assert.equal((await tool.execute({...query, snapshot: ui})).structuredContent.status, 'unique');
  const domQuery = {backend: 'dom', using: 'css selector', value: '#save', scope: {webview: '/roots/0', context: []}};
  assert.equal((await tool.execute({...domQuery, snapshot: ui})).structuredContent.status, 'unique');
  assert.deepEqual(ui, original);
  assert.deepEqual(selected, []);
  driver.executeScript = async (script, args) => {
    calls.push([script, args]);
    return ui;
  };
  assert.equal((await tool.execute(query)).isError, false);
  assert.equal((await tool.execute(domQuery)).isError, false);
  assert.deepEqual(
    calls.map(([, [args]]) => args.mode),
    ['automator', 'ultron'],
  );
  assert.equal(
    (await tool.execute({...domQuery, value: '.not-saved', snapshot: ui})).structuredContent.status,
    'unsupported',
  );
  assert.equal((await tool.execute({...domQuery, value: '#missing', snapshot: ui})).isError, true);
  for (const backend of ['automator', 'dom']) {
    assert.equal(
      (await tools.get('ultron_element').execute({backend, using: 'id', value: 'save', action: 'click'})).isError,
      true,
    );
  }
  assert.equal(calls.length, 2);
});

test('named state assertions compile to canonical Espresso conditions, preserving scope and timeout', async () => {
  const {tools, calls, selected} = setup();
  const target = {...matcher, scope: {class: 'RootMatchers', name: 'isDialog'}};
  const view = (name) => ({class: 'ViewMatchers', name});
  const not = (name) => ({name: 'not', args: [view(name)]});
  const cases = [
    ['isDisplayed', view('isDisplayed')],
    ['isNotDisplayed', not('isDisplayed')],
    ['isVisible', {class: 'ViewMatchers', name: 'withEffectiveVisibility', args: ['VISIBLE']}],
    ['isCompletelyDisplayed', view('isCompletelyDisplayed')],
    ['isEnabled', view('isEnabled')],
    ['isNotEnabled', not('isEnabled')],
    ['isSelected', view('isSelected')],
    ['isNotSelected', not('isSelected')],
    ['isClickable', view('isClickable')],
    ['isNotClickable', not('isClickable')],
    ['isChecked', view('isChecked')],
    ['isNotChecked', view('isNotChecked')],
    ['isFocusable', view('isFocusable')],
    ['isNotFocusable', not('isFocusable')],
    ['hasFocus', view('hasFocus')],
    ['isJavascriptEnabled', view('isJavascriptEnabled')],
  ];
  for (const [assertion, condition] of cases) {
    const output = await tools
      .get('ultron_assert')
      .execute({sessionId: 'chosen', matcher: target, assertion, timeoutMs: 0});
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.deepEqual(calls.at(-1), ['mobile: ultronAssert', [{matcher: target, condition, timeoutMs: 0}]]);
    assert.deepEqual(output.structuredContent, operation);
  }
  assert.ok(selected.every((id) => id === 'chosen'));
});

test('named existence, exact text/resource, substring and percentage checks have discoverable schemas', async () => {
  const {tools, calls} = setup();
  const cases = [
    [{assertion: 'exists'}, {exists: true}],
    [{assertion: 'doesNotExist'}, {exists: false}],
    [{assertion: 'hasText', text: ''}, {condition: {class: 'ViewMatchers', name: 'withText', args: ['']}}],
    [
      {assertion: 'hasText', resourceId: 2131951616},
      {condition: {class: 'ViewMatchers', name: 'withText', args: [2131951616]}},
    ],
    [
      {assertion: 'hasContentDescription', text: ''},
      {condition: {class: 'ViewMatchers', name: 'withContentDescription', args: ['']}},
    ],
    [
      {assertion: 'hasContentDescription', resourceId: 17039370},
      {condition: {class: 'ViewMatchers', name: 'withContentDescription', args: [17039370]}},
    ],
    [
      {assertion: 'textContains', text: 'Saved'},
      {condition: {class: 'ViewMatchers', name: 'withText', args: [{name: 'containsString', args: ['Saved']}]}},
    ],
    [
      {assertion: 'contentDescriptionContains', text: 'Save'},
      {
        condition: {
          class: 'ViewMatchers',
          name: 'withContentDescription',
          args: [{name: 'containsString', args: ['Save']}],
        },
      },
    ],
    [
      {assertion: 'isDisplayingAtLeast', percentage: 0},
      {condition: {class: 'ViewMatchers', name: 'isDisplayingAtLeast', args: [0]}},
    ],
    [
      {assertion: 'isDisplayingAtLeast', percentage: 100},
      {condition: {class: 'ViewMatchers', name: 'isDisplayingAtLeast', args: [100]}},
    ],
  ];
  const schema = z.toJSONSchema(tools.get('ultron_assert').parameters);
  assert.equal(schema.properties.assertion.enum.length, 23);
  assert.equal(schema.properties.percentage.minimum, 0);
  assert.equal(schema.properties.percentage.maximum, 100);
  for (const [named, expected] of cases) {
    assert.ok(schema.properties.assertion.enum.includes(named.assertion));
    const output = await tools.get('ultron_assert').execute({matcher, ...named});
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.deepEqual(calls.at(-1), ['mobile: ultronAssert', [{matcher, ...expected}]]);
  }
});

test('named assertion failures retain runtime status and evidence without outer retries', async () => {
  const {tools, driver, calls} = setup();
  const failed = {...operation, operation: {status: 'failure', error: 'Expected disabled button'}};
  driver.executeScript = async (script, args) => {
    calls.push([script, args]);
    return failed;
  };
  const output = await tools.get('ultron_assert').execute({matcher, assertion: 'isNotEnabled', timeoutMs: 0});
  assert.equal(output.isError, true);
  assert.deepEqual(output.structuredContent, failed);
  assert.deepEqual(JSON.parse(output.content[0].text), failed);
  assert.equal(calls.filter(([name]) => name === 'mobile: ultronAssert').length, 1);
});

test('rejects conflicting, missing and irrelevant named assertion arguments before consulting a device', async () => {
  const {tools, calls, selected} = setup();
  for (const args of [
    {assertion: 'unknown'},
    {assertion: null},
    {assertion: 'assertMatches'},
    {assertion: 'isEnabled', condition: matcher},
    {assertion: 'exists', exists: true},
    {assertion: 'doesNotExist', exists: false},
    {assertion: 'isDisplayed', exists: false},
    {assertion: 'hasText'},
    {assertion: 'hasContentDescription'},
    {assertion: 'hasText', text: 'OK', resourceId: 1},
    {assertion: 'hasContentDescription', text: '', resourceId: 1},
    {assertion: 'hasText', text: null},
    {assertion: 'hasText', text: 1},
    {assertion: 'hasText', resourceId: 0},
    {assertion: 'hasText', resourceId: 1.5},
    {assertion: 'hasText', resourceId: 2147483648},
    {assertion: 'hasContentDescription', resourceId: '1'},
    {assertion: 'textContains'},
    {assertion: 'contentDescriptionContains', resourceId: 1},
    {assertion: 'isDisplayingAtLeast'},
    {assertion: 'isDisplayingAtLeast', percentage: -1},
    {assertion: 'isDisplayingAtLeast', percentage: 101},
    {assertion: 'isDisplayingAtLeast', percentage: 50.5},
    {assertion: 'isDisplayingAtLeast', percentage: '50'},
    {assertion: 'isDisplayed', percentage: 50},
    {assertion: 'isEnabled', text: ''},
    {assertion: 'doesNotExist', resourceId: 1},
    {text: 'Saved'},
    {resourceId: 1},
    {percentage: 0},
    {condition: matcher, text: 'Saved'},
    {exists: false, percentage: 100},
  ]) {
    const output = await tools.get('ultron_assert').execute({matcher, ...args});
    assert.equal(output.isError, true, JSON.stringify(args));
  }
  assert.deepEqual(calls, []);
  assert.deepEqual(selected, []);
});

test('legacy default, exists and custom condition calls remain unchanged', async () => {
  const {tools, calls} = setup();
  const condition = {class: 'ViewMatchers', name: 'withText', args: [{name: 'containsString', args: ['Saved']}]};
  for (const args of [
    {matcher},
    {matcher, exists: true, timeoutMs: 0},
    {matcher, exists: false},
    {matcher, condition, exists: true},
    {matcher, condition},
  ]) {
    assert.equal((await tools.get('ultron_assert').execute(args)).isError, false);
    assert.deepEqual(calls.at(-1), ['mobile: ultronAssert', [args]]);
  }
});

test('exposes all standard Ultron actions and preserves offsets, key options, false and zero', async () => {
  const {tools, calls} = setup();
  const actions = [
    {action: 'doubleClick'},
    {action: 'closeSoftKeyboard'},
    {action: 'clickTopLeft', offsetX: 8, offsetY: 8},
    {action: 'clickTopCenter', offsetY: 8},
    {action: 'clickTopRight', offsetX: -8, offsetY: 8},
    {action: 'clickCenterRight', offsetX: -8},
    {action: 'clickBottomRight', offsetX: -8, offsetY: -8},
    {action: 'clickBottomCenter', offsetY: -8},
    {action: 'clickBottomLeft', offsetX: 8, offsetY: -8},
    {action: 'clickCenterLeft', offsetX: 8},
    {action: 'pressKey', keyCode: 29},
    {action: 'pressKey', keyCode: 29, metaState: 1, flags: 0},
    {action: 'clickTopLeft', offsetX: 0, offsetY: 0, autoScroll: false, name: 'Continue', timeoutMs: 0},
  ];
  const schema = z.toJSONSchema(tools.get('ultron_act').parameters);
  for (const action of actions) {
    assert.ok(schema.properties.action.enum.includes(action.action));
    const args = {matcher, ...action};
    const output = await tools.get('ultron_act').execute({sessionId: 'chosen', ...args});
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.deepEqual(calls.at(-1), ['mobile: ultronAct', [args]]);
    assert.deepEqual(output.structuredContent.snapshot, snapshot);
  }
});

test('rejects malformed or irrelevant action options before dispatching any device calls', async () => {
  const {tools, calls} = setup();
  for (const action of [
    {action: 'pressKey'},
    {action: 'pressKey', keyCode: 0},
    {action: 'pressKey', keyCode: -1},
    {action: 'pressKey', keyCode: 29.5},
    {action: 'pressKey', keyCode: 2147483648},
    {action: 'pressKey', keyCode: 29, metaState: -1},
    {action: 'pressKey', keyCode: 29, flags: '0'},
    {action: 'click', keyCode: 29},
    {action: 'click', metaState: 0},
    {action: 'click', flags: 0},
    {action: 'click', offsetX: 1},
    {action: 'clickTopCenter', offsetX: 0},
    {action: 'clickCenterRight', offsetY: 0},
    {action: 'clickTopLeft', offsetX: 0.5},
    {action: 'clickTopLeft', offsetY: -2147483649},
    {action: 'click', autoScroll: 'false'},
    {action: 'click', name: ''},
  ]) {
    const output = await tools.get('ultron_act').execute({matcher, ...action});
    assert.equal(output.isError, true, JSON.stringify(action));
  }
  assert.deepEqual(calls, []);
});

test('unavailable observation fails while a partial tree remains usable', async () => {
  const {tools, driver} = setup();
  driver.executeScript = async () => ({...snapshot, status: 'unavailable'});
  assert.equal((await tools.get('ultron_snapshot').execute({})).isError, true);
  driver.executeScript = async () => ({...snapshot, status: 'partial', truncated: true});
  assert.equal((await tools.get('ultron_snapshot').execute({})).isError, false);
  driver.executeScript = async () => null;
  assert.equal((await tools.get('ultron_snapshot').execute({})).isError, true);
});

test('rejects invalid arguments and missing sessions before device calls', async () => {
  const {tools, calls} = setup();
  for (const args of [
    {matcher, action: 'typeText'},
    {matcher, action: 'click', timeoutMs: -1},
  ]) {
    assert.equal((await tools.get('ultron_act').execute(args)).isError, true);
  }
  assert.equal((await tools.get('ultron_assert').execute({matcher, exists: false, condition: matcher})).isError, true);
  assert.equal((await tools.get('ultron_snapshot').execute({sessionId: 'missing'})).isError, true);
  assert.deepEqual(calls, []);
});

test('never retries a mutation after transport loss', async () => {
  const {tools, driver} = setup();
  let attempts = 0;
  driver.executeScript = async () => {
    attempts++;
    throw new Error('Response lost');
  };
  const output = await tools.get('ultron_act').execute({matcher, action: 'click'});
  assert.equal(output.isError, true);
  assert.equal(attempts, 1);
});

test('serializes calls per selected driver and recovers its queue after failures', async () => {
  const {tools, driver, calls} = setup();
  let release;
  let started;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const ready = new Promise((resolve) => {
    started = resolve;
  });
  driver.executeScript = async (script) => {
    calls.push([script]);
    if (script.endsWith('ultronAct')) {
      started();
      await gate;
      throw new Error('Response lost');
    }
    return snapshot;
  };
  const first = tools.get('ultron_act').execute({matcher, action: 'click'});
  await ready;
  const second = tools.get('ultron_snapshot').execute({});
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(
    calls.some(([name]) => name === 'mobile: ultronSnapshot'),
    false,
  );
  release();
  assert.equal((await first).isError, true);
  assert.equal((await second).isError, false);
});

test('settings can select Compose and preserve false; last result can be null', async () => {
  const {tools, calls} = setup();
  const output = await tools.get('ultron_settings').execute({snapshotEnabled: false, driver: 'compose'});
  assert.equal(output.structuredContent.driver, 'compose');
  assert.deepEqual(calls[0], ['updateSettings', {ultronSnapshotEnabled: false, driver: 'compose'}]);
  const last = await tools.get('ultron_last_result').execute({});
  assert.equal(last.content[0].text, 'null');
  assert.equal(last.isError, false);
});

test('matcher checks accept saved dumps without consulting a device/session', async () => {
  const {tools, core, calls} = setup();
  core.getDriver = () => {
    throw new Error('Offline check must not request a driver');
  };
  const ui = {status: 'captured', truncated: false, errors: [], roots: [{type: 'View', text: 'Continue'}]};
  const output = await tools.get('ultron_check_matcher').execute({backend: 'espresso', matcher, snapshot: ui});
  assert.equal(output.isError, false);
  assert.equal(output.structuredContent.status, 'unique');
  assert.deepEqual(calls, []);
});

test('strict preflight blocks zero/multiple/unknown targets without dispatching mutations or settings', async () => {
  for (const ui of [
    {status: 'captured', truncated: false, errors: [], roots: []},
    {
      status: 'captured',
      truncated: false,
      errors: [],
      roots: [
        {
          type: 'View',
          children: [
            {type: 'View', text: 'Continue'},
            {type: 'View', text: 'Continue'},
          ],
        },
      ],
    },
    {status: 'partial', truncated: true, errors: [], roots: [{type: 'View', text: 'Continue'}]},
  ]) {
    const {tools, driver, calls} = setup();
    driver.executeScript = async (script, args) => {
      calls.push([script, args]);
      return ui;
    };
    const output = await tools.get('ultron_act').execute({matcher, action: 'click', preflight: true});
    assert.equal(output.isError, true);
    assert.equal(output.structuredContent.operation.status, 'not_executed');
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], 'mobile: ultronSnapshot');
  }
});

test('successful preflight includes its evidence and does not forward MCP-only fields to Android', async () => {
  const {tools, driver, calls} = setup();
  driver.executeScript = async (script, args) => {
    calls.push([script, args]);
    return script.endsWith('ultronSnapshot')
      ? {status: 'captured', truncated: false, errors: [], roots: [{type: 'View', text: 'Continue'}]}
      : operation;
  };
  const output = await tools.get('ultron_act').execute({matcher, action: 'click', preflight: true, timeoutMs: 0});
  assert.equal(output.isError, false);
  assert.equal(output.structuredContent.preflight.status, 'unique');
  assert.deepEqual(calls.find(([name]) => name === 'mobile: ultronAct')[1], [{matcher, action: 'click', timeoutMs: 0}]);
});

test('RecyclerView operations validate item targets, preserve scope and pass bounded searches', async () => {
  const {tools, calls, driver} = setup();
  const tool = tools.get('ultron_recycler');
  const itemMatcher = {class: 'ViewMatchers', name: 'hasDescendant', args: [matcher]};
  const target = {...matcher, scope: {class: 'RootMatchers', name: 'isDialog'}};
  for (const args of [
    {matcher: target, action: 'click', position: 0, childMatcher: matcher, timeoutMs: 0},
    {matcher, action: 'scrollToItem', itemMatcher, searchLimit: 10},
    {matcher, action: 'replaceText', position: 0, childMatcher: matcher, text: ''},
    {matcher, action: 'assertItemCount', itemCount: 0},
    {matcher, action: 'assertItemExists', itemMatcher},
    {matcher, action: 'assertItemNotExists', position: 0},
    {matcher, action: 'assertItem', position: 0, condition: matcher},
  ]) {
    assert.equal((await tool.execute(args)).isError, false);
    assert.deepEqual(calls.at(-1), ['mobile: ultronRecycler', [args]]);
  }
  const before = calls.length;
  for (const args of [
    {action: 'click'},
    {action: 'click', position: -1},
    {action: 'click', position: 0.5},
    {action: 'click', position: 0, itemMatcher},
    {action: 'click', position: 0, searchLimit: 10},
    {action: 'scrollToItem', itemMatcher, searchLimit: 0},
    {action: 'scrollToItem', itemMatcher, searchLimit: 10001},
    {action: 'scrollToItem', position: 0, childMatcher: matcher},
    {action: 'assertItemCount'},
    {action: 'assertItemCount', itemCount: -1},
    {action: 'assertItemCount', itemCount: 0, position: 0},
    {action: 'assertItem', position: 0},
    {action: 'click', position: 0, condition: matcher},
    {action: 'replaceText', position: 0},
    {action: 'click', position: 0, text: ''},
    {action: 'click', itemMatcher: target},
    {action: 'click', position: 0, childMatcher: target},
    {action: 'assertItem', position: 0, condition: target},
  ]) {
    assert.equal((await tool.execute({matcher, ...args})).isError, true, JSON.stringify(args));
  }
  assert.equal(calls.length, before);
  driver.executeScript = async () => ({...operation, operation: {status: 'failure', error: 'Search limit reached'}});
  const failed = await tool.execute({matcher, action: 'assertItemNotExists', itemMatcher, searchLimit: 10});
  assert.equal(failed.isError, true);
  assert.deepEqual(failed.structuredContent.snapshot, snapshot);
});
