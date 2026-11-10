import assert from 'node:assert/strict';
import {test} from 'node:test';
import {z} from 'zod';
import UltronMcpPlugin from '../index.mjs';

const matcher = {name: 'hasTestTag', args: ['target']};
function setup() {
  const tools = new Map();
  const calls = [];
  const value = {operation: {status: 'success', backend: 'compose'}, snapshot: {status: 'captured', roots: []}};
  const driver = {
    getSettings: async () => {calls.push(['settings']); return {driver: 'espresso', ultronSnapshotEnabled: true};},
    executeScript: async (command, args) => {calls.push([command, args]); return value;},
  };
  new UltronMcpPlugin().register({addTool: t => tools.set(t.name, t)}, {getDriver: () => driver});
  return {tools, calls, driver, value};
}

test('Compose tools publish schemas and forward actions without changing the active driver', async () => {
  const {tools, calls, value} = setup();
  const tool = tools.get('ultron_compose_act');
  const schema = z.toJSONSchema(tool.parameters);
  assert.equal(schema.type, 'object');
  for (const args of [
    {action: 'click', options: {position: 'topRight', offsetX: -8, offsetY: 0}},
    {action: 'longClick', options: {durationMs: 1200}}, {action: 'doubleClick', options: {delayMs: 100}},
    {action: 'replaceText', text: ''}, {action: 'inputText', text: 'Hello'}, {action: 'setText', text: ''},
    {action: 'scrollTo'}, {action: 'scrollToIndex', options: {index: 0}},
    {action: 'scrollToKey', options: {keyType: 'int', key: 0}},
    {action: 'scrollToKey', options: {keyType: 'long', key: '9223372036854775807'}},
    {action: 'scrollToNode', itemMatcher: {name: 'hasText', args: ['Row 100']}},
    {action: 'swipeUp', options: {durationMs: 500, startXOffset: 0, endYOffset: -10}},
    {action: 'setProgress', options: {value: 0}},
    {action: 'selectText', options: {start: 4, end: 0}},
    {action: 'setSelection', options: {start: 0, end: 0, traversalMode: false}},
    {action: 'customAccessibilityAction', options: {label: 'Bookmark'}},
    {action: 'pressKey', options: {keyCode: 29, metaState: 0, keyAction: 'up'}},
    ...['imeAction', 'clearText', 'expand', 'collapse', 'dismiss', 'copyText', 'cutText', 'pasteText'].map(action => ({action})),
  ]) {
    const payload = {matcher, ...args, useUnmergedTree: false, timeoutMs: 0};
    const output = await tool.execute({sessionId: 'chosen', ...payload});
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.deepEqual(output.structuredContent, value);
    assert.deepEqual(calls.at(-1), ['mobile: ultronComposeAct', [payload]]);
    assert.ok(schema.properties.action.enum.includes(args.action));
  }
});

test('Compose assertions preserve arrays, empty text, conditions, float ranges and options', async () => {
  const {tools, calls} = setup();
  const tool = tools.get('ultron_compose_assert');
  const schema = z.toJSONSchema(tool.parameters);
  for (const args of [
    {}, ...['exists', 'doesNotExist', 'isDisplayed', 'isNotDisplayed', 'isEnabled', 'isNotEnabled', 'isFocused',
      'isNotFocused', 'isSelected', 'isNotSelected', 'isSelectable', 'isToggleable', 'isOn', 'isOff',
      'isIndeterminate', 'hasClickAction', 'hasNoClickAction'].map(assertion => ({assertion})),
    {assertion: 'textEquals', expected: ['Alpha', 'Beta'], options: {includeEditableText: false}},
    {assertion: 'textEquals', expected: []}, {assertion: 'contentDescriptionEquals', expected: ['']},
    {assertion: 'textContains', text: '', options: {substring: false, ignoreCase: true}},
    {assertion: 'contentDescriptionContains', text: 'save'}, {assertion: 'valueEquals', text: ''},
    {assertion: 'rangeInfoEquals', options: {current: .5, min: 0, max: 1, steps: 0}},
    {assertion: 'widthIsAtLeast', options: {dp: 48}}, {assertion: 'heightIsEqualTo', options: {dp: 0}},
    {assertion: 'matches', condition: {name: 'propertyEquals', args: ['ToggleableState', 'Indeterminate']}},
  ]) {
    const output = await tool.execute({matcher, ...args});
    assert.equal(output.isError, false, JSON.stringify(output));
    assert.deepEqual(calls.at(-1), ['mobile: ultronComposeAssert', [{matcher, ...args}]]);
    if (args.assertion) {assert.ok(schema.properties.assertion.enum.includes(args.assertion));}
  }
});

test('malformed, missing, unsafe and irrelevant Compose options fail before consulting Android', async () => {
  const {tools, calls} = setup();
  for (const args of [
    {action: 'click', text: ''}, {action: 'click', options: {index: 0}}, {action: 'click', preflight: true},
    {action: 'click', options: {position: 'outside'}}, {action: 'click', options: {offsetX: .5}},
    {action: 'longClick', options: {durationMs: 0}}, {action: 'swipeUp', options: {durationMs: 60001}},
    {action: 'replaceText'}, {action: 'scrollToNode'}, {action: 'scrollTo', itemMatcher: matcher},
    {action: 'scrollToIndex'}, {action: 'scrollToIndex', options: {index: -1}},
    {action: 'scrollToKey', options: {key: 0}}, {action: 'scrollToKey', options: {keyType: 'int', key: '0'}},
    {action: 'scrollToKey', options: {keyType: 'long', key: '9223372036854775808'}},
    {action: 'scrollToKey', options: {keyType: 'long', key: '1.2'}},
    {action: 'setProgress', options: {value: Infinity}}, {action: 'setProgress', options: {value: 1e39}},
    {action: 'customAccessibilityAction', options: {label: ''}}, {action: 'selectText', options: {start: 0}},
    {action: 'setSelection', options: {start: 0, end: 1}}, {action: 'pressKey', options: {keyCode: 0}},
    {action: 'click', assertion: 'exists'}, {action: 'click', useUnmergedTree: 'false'},
  ]) {assert.equal((await tools.get('ultron_compose_act').execute({matcher, ...args})).isError, true, JSON.stringify(args));}
  for (const args of [
    {assertion: 'textEquals'}, {assertion: 'textEquals', expected: 'Hello'}, {assertion: 'textEquals', expected: [1]},
    {assertion: 'textContains'}, {assertion: 'isEnabled', expected: []}, {assertion: 'isOff', text: ''},
    {assertion: 'matches'}, {condition: matcher}, {assertion: 'exists', options: {substring: true}},
    {assertion: 'rangeInfoEquals', options: {current: 2, min: 0, max: 1}},
    {assertion: 'rangeInfoEquals', options: {current: 0, min: 1, max: 0}},
    {assertion: 'widthIsAtLeast', options: {dp: -1}}, {assertion: 'heightIsEqualTo'},
  ]) {assert.equal((await tools.get('ultron_compose_assert').execute({matcher, ...args})).isError, true, JSON.stringify(args));}
  assert.deepEqual(calls, []);
});

test('Compose matcher DSL is bounded and checks nested shapes and argument types', async () => {
  const {tools, calls} = setup();
  let deep = matcher;
  for (let i = 0; i < 18; i++) {deep = {name: 'not', args: [deep]};}
  for (const target of [
    {...matcher, class: 'ViewMatchers'}, {...matcher, scope: matcher}, {name: 'arbitraryMethod'},
    {name: 'hasText', args: ['X', 'false']}, {name: 'hasTestTag', args: []}, {name: 'isEnabled', args: [true]},
    {name: 'allOf', args: []}, {name: 'hasParent', args: ['text']}, deep,
  ]) {assert.equal((await tools.get('ultron_compose_act').execute({matcher: target, action: 'click'})).isError, true);}
  assert.deepEqual(calls, []);
  const composite = {name: 'allOf', args: [matcher, {name: 'hasAnyAncestor', args: [{name: 'hasTestTag', args: ['parent']}]}]};
  assert.equal((await tools.get('ultron_compose_act').execute({matcher: composite, action: 'click'})).isError, false);
});

test('Compose failures retain evidence and a lost mutating response is never retried', async () => {
  const {tools, driver, value} = setup();
  const failed = {...value, operation: {status: 'failure', backend: 'compose', error: 'Ambiguous semantics'}};
  driver.executeScript = async () => failed;
  const result = await tools.get('ultron_compose_assert').execute({matcher});
  assert.equal(result.isError, true);
  assert.deepEqual(result.structuredContent, failed);
  let attempts = 0;
  driver.executeScript = async () => {attempts++; throw new Error('Response lost');};
  assert.equal((await tools.get('ultron_compose_act').execute({matcher, action: 'click'})).isError, true);
  assert.equal(attempts, 1);
});
