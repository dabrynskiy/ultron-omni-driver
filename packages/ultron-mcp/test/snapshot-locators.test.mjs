import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';

import {checkSnapshotMatcher as check, freshMatcherCheck} from '../snapshot-match.mjs';

const dump = (roots, props = {}) => ({status: 'captured', truncated: false, errors: [], roots, ...props});
const accessibilityNode = (props = {}) => ({
  type: 'AccessibilityNode',
  source: 'accessibility',
  text: null,
  contentDescription: null,
  resourceId: null,
  className: 'android.widget.Button',
  packageName: 'app',
  enabled: true,
  checkable: false,
  ...props,
});
const accessibility = (...children) =>
  dump([accessibilityNode({window: {id: 1}, windowId: 1, children})], {backend: 'automator', mode: 'automator'});
const dom = (tagName, props = {}) => ({
  type: 'DomNode',
  source: 'web',
  tagName,
  namespaceURI: 'http://www.w3.org/1999/xhtml',
  selector: `html > body:nth-of-type(1) > ${tagName}:nth-of-type(1)`,
  attributes: {},
  context: [],
  ...props,
});
const document = (children, props = {}) =>
  dom('html', {
    selector: 'html',
    children: [dom('body', {selector: 'html > body:nth-of-type(1)', context: props.context ?? [], children})],
    ...props,
  });
const host = (children, props = {}) => ({
  type: 'View',
  source: 'espresso',
  className: 'android.webkit.WebView',
  dom_status: 'captured',
  children,
  ...props,
});
const web = (...children) => dump([host([document(children)])]);
const query = (snapshot, using, value, extra = {}) => check({snapshot, backend: 'dom', using, value, ...extra});

test('accessibility direct locators count only accessibility nodes and retain window evidence', () => {
  const ui = accessibility(
    accessibilityNode({text: 'Save', resourceId: 'app:id/save', contentDescription: 'Submit'}),
    dom('button', {text: 'Save'}),
  );
  for (const [using, value] of [
    ['text', 'Save'],
    ['id', 'app:id/save'],
    ['accessibility id', 'Submit'],
  ]) {
    const report = check({snapshot: ui, backend: 'automator', using, value});
    assert.equal(report.status, 'unique');
    assert.equal(report.matches[0].path, '/roots/0/children/0');
    assert.equal(report.matches[0].window.id, 1);
  }
  assert.equal(check({snapshot: ui, backend: 'automator', using: 'text', value: 'Missing'}).status, 'not_found');
  assert.equal(
    check({snapshot: ui, backend: 'automator', using: 'class name', value: 'android.widget.Button'}).status,
    'ambiguous',
  );
  assert.equal(check({snapshot: ui, backend: 'espresso', using: 'text', value: 'Save'}).status, 'unsupported');
  assert.equal(check({snapshot: web(), backend: 'automator', using: 'text', value: 'Save'}).status, 'unsupported');
});

test('UiSelector exact strings and booleans compose without executing supplied code', () => {
  const ui = accessibility(
    accessibilityNode({
      text: 'Save "now"',
      resourceId: 'app:id/save',
      clickable: true,
      is_checked: true,
      checkable: true,
    }),
    accessibilityNode({text: 'Save "now"', clickable: false}),
  );
  const value = 'new UiSelector().text("Save \\"now\\"").resourceId("app:id/save").clickable(true).checked(true)';
  assert.equal(check({snapshot: ui, backend: 'automator', using: '-android uiautomator', value}).status, 'unique');
  assert.equal(
    check({snapshot: ui, backend: 'automator', using: '-android uiautomator', value: 'UiSelector().checked(false)'})
      .observedCount,
    2,
  );
  for (const value of [
    'new UiSelector().textMatches("Save.*")',
    'new UiSelector().text("Save").instance(0)',
    'new UiSelector().text("a").text("b")',
    'new UiSelector().enabled("true")',
    'new UiSelector(); process.exit()',
    'new UiSelector().childSelector(new UiSelector().text("Save"))',
  ]) {
    assert.equal(
      check({snapshot: ui, backend: 'automator', using: '-android uiautomator', value}).status,
      'unsupported',
      value,
    );
  }
});

test('accessibility scope counts across windows unless narrowed explicitly', () => {
  const ui = dump(
    [accessibilityNode({text: 'Save', window: {id: 1}}), accessibilityNode({text: 'Save', window: {id: 2}})],
    {backend: 'automator'},
  );
  const args = {snapshot: ui, backend: 'automator', using: 'text', value: 'Save'};
  assert.equal(check(args).status, 'ambiguous');
  assert.equal(check({...args, scope: {windowId: 2}}).status, 'unique');
  assert.equal(check({...args, scope: {windowId: 3}}).status, 'not_found');
  delete ui.roots[1].window;
  assert.equal(check({...args, scope: {windowId: 1}}).status, 'indeterminate');
});

test('DOM checks generated structural selectors, tags, IDs and saved attributes', () => {
  const ui = web(
    dom('button', {
      attributes: {id: 'save', role: 'button', 'aria-label': 'Save'},
      children: [dom('span', {selector: 'html > body:nth-of-type(1) > button:nth-of-type(1) > span:nth-of-type(1)'})],
    }),
  );
  for (const value of [
    'html > body:nth-of-type(1) > button:nth-of-type(1)',
    'body button#save[role="button"]',
    '[aria-label="Save"]',
    '[role]',
    'BUTTON',
    'button > span',
    'html span',
    '*[id=save]',
  ]) {
    assert.equal(query(ui, 'css selector', value).status, 'unique', value);
  }
  const report = query(ui, 'id', 'save');
  assert.equal(report.status, 'unique');
  assert.equal(report.matches[0].webview, '/roots/0');
  assert.deepEqual(report.matches[0].context, []);
  assert.equal(query(ui, 'css selector', 'body > span').status, 'not_found');
  assert.equal(query(ui, 'css selector', '#missing').status, 'not_found');
});

test('CSS integer indices use original DOM positions rather than filtered sibling arrays', () => {
  const ui = web(dom('button', {selector: 'html > body:nth-of-type(1) > button:nth-of-type(2)'}));
  assert.equal(query(ui, 'css selector', 'button:nth-of-type(2)').status, 'unique');
  assert.equal(query(ui, 'css selector', 'button:nth-of-type(1)').status, 'not_found');
});

test('DOM duplicate IDs remain ambiguous and bounded candidates retain true count', () => {
  const ui = web(dom('button', {attributes: {id: 'save'}}), dom('input', {attributes: {id: 'save'}}));
  const report = query(ui, 'css selector', '#save', {limit: 1});
  assert.equal(report.status, 'ambiguous');
  assert.equal(report.observedCount, 2);
  assert.equal(report.matchesTruncated, true);
});

test('CSS escaped IDs and quoted attribute values are decoded without evaluation', () => {
  const ui = web(dom('button', {attributes: {id: '123:a', name: 'a"b'}}));
  assert.equal(query(ui, 'css selector', '#\\31 23\\:a').status, 'unique');
  assert.equal(query(ui, 'css selector', '[name="a\\"b"]').status, 'unique');
  assert.equal(query(ui, 'css selector', "[name='a\"b']").status, 'unique');
});

test('CSS case rules do not produce false certainty in quirks mode or for HTML type attributes', () => {
  const button = dom('button', {compatMode: 'BackCompat', attributes: {id: 'save', type: 'button'}});
  assert.equal(query(web(button), 'css selector', '#SAVE').status, 'unique');
  assert.equal(query(web(button), 'css selector', '[id="SAVE"]').status, 'not_found');
  assert.equal(query(web(button), 'css selector', '[type="BUTTON"]').status, 'indeterminate');
  button.compatMode = 'CSS1Compat';
  assert.equal(query(web(button), 'css selector', '#SAVE').status, 'not_found');
  delete button.compatMode;
  assert.equal(query(web(button), 'css selector', '#SAVE').status, 'indeterminate');
});

test('DOM scope isolates WebViews and frame/shadow contexts without crossing boundaries', () => {
  const frameContext = [{type: 'frame', selector: 'html > body:nth-of-type(1) > iframe:nth-of-type(1)'}];
  const shadowContext = [{type: 'shadow', selector: 'html > body:nth-of-type(1) > section:nth-of-type(1)'}];
  const button = (context = []) => dom('button', {attributes: {id: 'save'}, context});
  const ui = dump([
    host([
      document([
        button(),
        dom('iframe', {children: [document([button(frameContext)], {context: frameContext})]}),
        dom('section', {
          children: [dom('#shadow-root', {selector: '', context: shadowContext, children: [button(shadowContext)]})],
        }),
      ]),
    ]),
    host([document([button()])]),
  ]);
  assert.equal(query(ui, 'id', 'save').observedCount, 2);
  assert.equal(query(ui, 'id', 'save', {scope: {webview: '/roots/0'}}).status, 'unique');
  assert.equal(query(ui, 'id', 'save', {scope: {webview: '/roots/0', context: frameContext}}).status, 'unique');
  assert.equal(query(ui, 'id', 'save', {scope: {context: shadowContext}}).status, 'unique');
  assert.equal(query(ui, 'css selector', 'html > body > button', {scope: {context: frameContext}}).status, 'unique');
  assert.equal(query(ui, 'css selector', 'button', {scope: {context: shadowContext}}).status, 'unique');
  assert.equal(query(ui, 'css selector', 'iframe button', {scope: {context: frameContext}}).status, 'not_found');
  assert.equal(query(ui, 'css selector', 'section button', {scope: {context: shadowContext}}).status, 'not_found');
  assert.equal(query(ui, 'css selector', '* > button', {scope: {context: shadowContext}}).status, 'not_found');
  assert.equal(query(ui, 'id', 'save', {scope: {webview: '/roots/9'}}).status, 'not_found');
});

test('DOM is checkable in either native snapshot mode without counting accessibility mirrors', () => {
  const ui = accessibility(accessibilityNode({text: 'Save'}), {
    ...host([document([dom('button', {attributes: {id: 'save'}})])]),
    type: 'AccessibilityNode',
  });
  assert.equal(query(ui, 'css selector', '#save').status, 'unique');
});

test('partial trees cannot prove absence or uniqueness; multiple definite matches still prove ambiguity', () => {
  for (const backend of ['automator', 'dom']) {
    const ui =
      backend === 'automator'
        ? accessibility(accessibilityNode({text: 'Save'}))
        : web(dom('button', {attributes: {id: 'Save'}}));
    const args = {snapshot: ui, backend, using: backend === 'dom' ? 'id' : 'text', value: 'Save'};
    for (const partial of [{truncated: true}, {status: 'partial'}, {errors: ['collection failed']}]) {
      assert.equal(check({...args, snapshot: {...ui, ...partial}}).status, 'indeterminate');
      assert.equal(check({...args, value: 'Missing', snapshot: {...ui, ...partial}}).status, 'indeterminate');
    }
    ui.roots.push(structuredClone(ui.roots[0]));
    assert.equal(check({...args, snapshot: {...ui, truncated: true}}).status, 'ambiguous');
  }
});

test('redacted, missing and bounded properties do not yield false positive or negative certainty', () => {
  const ui = accessibility(accessibilityNode({text: '[REDACTED]', sensitive: true}));
  assert.equal(check({snapshot: ui, backend: 'automator', using: 'text', value: 'secret'}).status, 'indeterminate');
  delete ui.roots[0].children[0].text;
  assert.equal(check({snapshot: ui, backend: 'automator', using: 'text', value: 'Save'}).status, 'indeterminate');
  for (const props of [
    {attributes: undefined},
    {sensitive: true, attributes: {name: '[REDACTED]'}},
    {attributes: {name: 'a'.repeat(4096)}},
  ]) {
    assert.equal(query(web(dom('input', props)), 'css selector', '[name="secret"]').status, 'indeterminate');
  }
  assert.equal(query(dump([host([], {dom_status: 'unavailable'})]), 'id', 'save').status, 'indeterminate');
  const old = web(dom('button', {namespaceURI: undefined}));
  assert.equal(query(old, 'css selector', 'BUTTON').status, 'indeterminate');
});

test('unsupported CSS, scopes and cross-backend matchers are rejected explicitly', () => {
  const ui = web(dom('button'));
  for (const value of [
    '.button',
    '[class="button"]',
    ':checked',
    'button + input',
    'button, input',
    'button:nth-of-type(odd)',
    '[role*="but"]',
    'button >',
    '',
    '[onclick]',
    '[name=123]',
    '#-1',
    '-',
    '#',
  ]) {
    assert.equal(query(ui, 'css selector', value).status, 'unsupported', value);
  }
  for (const scope of [
    {webview: 'not-a-path'},
    {context: [{type: 'iframe', selector: 'iframe'}]},
    {unknown: true},
    {windowId: 1.5},
  ]) {
    assert.equal(query(ui, 'id', 'save', {scope}).status, 'unsupported');
  }
  assert.equal(query(ui, 'id', 'save', {matcher: {name: 'withText', args: ['Save']}}).status, 'unsupported');
  assert.equal(
    check({snapshot: accessibility(), backend: 'automator', using: 'id', value: 'save', scope: {context: []}}).status,
    'unsupported',
  );
});

test('fresh checks choose the matching observation mode without changing settings', async () => {
  const calls = [];
  const driver = {
    executeScript: async (script, [args]) => {
      calls.push([script, args]);
      return args.mode === 'automator'
        ? accessibility(accessibilityNode({text: 'Save'}))
        : web(dom('button', {attributes: {id: 'save'}}));
    },
  };
  assert.equal(
    (await freshMatcherCheck(driver, {backend: 'automator', using: 'text', value: 'Save'})).preflight.status,
    'unique',
  );
  assert.equal(
    (await freshMatcherCheck(driver, {backend: 'dom', using: 'id', value: 'save'})).preflight.status,
    'unique',
  );
  assert.deepEqual(
    calls.map(([, args]) => args.mode),
    ['automator', 'ultron'],
  );
});

test('real collector output can be checked offline, preserving namespaces and generated paths', async () => {
  const source = await readFile(
    new URL('../../../espresso-server/library/src/main/assets/ultron-webview-snapshot.js', import.meta.url),
    'utf8',
  );
  const element = (localName, attributes = {}) => ({
    localName,
    namespaceURI: 'http://www.w3.org/1999/xhtml',
    nodeType: 1,
    firstChild: null,
    firstElementChild: null,
    nextElementSibling: null,
    getAttribute: (key) => attributes[key] ?? null,
    getBoundingClientRect: () => ({left: 0, top: 0, right: 100, bottom: 50, width: 100, height: 50}),
  });
  const html = element('html');
  const body = element('body');
  const save = element('button', {id: 'save', 'aria-label': 'Save'});
  html.firstElementChild = body;
  body.firstElementChild = save;
  const document = {
    compatMode: 'CSS1Compat',
    documentElement: html,
    defaultView: {getComputedStyle: () => ({display: 'block', visibility: 'visible'})},
  };
  for (const node of [html, body, save]) {
    node.ownerDocument = document;
  }
  const collected = JSON.parse(
    JSON.stringify(runInNewContext(`(${source})(100,100)`, {document, CSS: {escape: (value) => value}})),
  );
  const ui = dump([host(collected.roots)], {truncated: collected.truncated, errors: collected.errors});
  const saved = ui.roots[0].children[0].children[0].children[0];
  assert.equal(query(ui, 'css selector', saved.selector).status, 'unique');
  assert.equal(query(ui, 'css selector', 'BUTTON#save[aria-label="Save"]').status, 'unique');
});
