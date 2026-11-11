import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {mkdtemp, writeFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';

import {checkSnapshotMatcher as check} from '../snapshot-match.mjs';

const property = (name, value) => ({class: 'LlmViewMatchers', name: 'propertyEquals', args: [name, value]});
const text = (value) => ({class: 'ViewMatchers', name: 'withText', args: [value]});
const view = (props) => ({
  type: 'View',
  className: 'android.widget.TextView',
  enabled: true,
  tag: null,
  tag_is_stringified: false,
  contentDescription: null,
  transformed_text: null,
  effective_visibility: 'VISIBLE',
  ...props,
});
const root = (children, category = 'application') =>
  view({className: 'android.widget.FrameLayout', window: {category, is_dialog: category === 'dialog'}, children});
const dump = (roots) => ({
  schemaVersion: 1,
  snapshotId: 'test-snapshot',
  capturedAtMs: 123,
  status: 'captured',
  truncated: false,
  errors: [],
  roots,
});
const snapshot = (...children) => dump([root(children)]);
const node = (props) => ({type: 'SemanticsNode', node_id: 7, config: {}, ...props});
const compose = (children) =>
  view({
    className: 'AndroidComposeView',
    children: [
      node({
        semantics_root: true,
        semantics_tree: 'unmerged',
        children,
      }),
    ],
  });

test('reports 0, 1 and multiple View targets, with useful candidate evidence', () => {
  const ui = snapshot(view({text: 'Save', resourceId: 'app:id/save', bounds: {l: 1, t: 2, r: 3, b: 4}}));
  assert.equal(check({snapshot: ui, matcher: text('Missing')}).status, 'not_found');
  const unique = check({snapshot: ui, matcher: text('Save')});
  assert.equal(unique.status, 'unique');
  assert.equal(unique.exact, true);
  assert.equal(unique.snapshotId, 'test-snapshot');
  assert.equal(unique.matches[0].resourceId, 'app:id/save');
  assert.equal(unique.matches[0].path, '/roots/0/children/0');
  ui.roots[0].children.push(view({text: 'Save', effective_visibility: 'GONE'}));
  const ambiguous = check({snapshot: ui, matcher: text('Save'), limit: 1});
  assert.equal(ambiguous.status, 'ambiguous');
  assert.equal(ambiguous.observedCount, 2);
  assert.equal(ambiguous.matchesTruncated, true);
});

test('native and Compose representations are counted separately without hiding duplicate tags', () => {
  const ui = snapshot(
    view({text: 'Save'}),
    compose([
      node({test_tag: 'save', config: {Text: ['Save']}}),
      node({test_tag: 'save', config: {Text: ['Save']}, effective_visibility: 'GONE'}),
    ]),
  );
  assert.equal(check({snapshot: ui, matcher: text('Save')}).observedCount, 1);
  const report = check({snapshot: ui, backend: 'compose', using: '-android viewtag', value: 'save'});
  assert.equal(report.status, 'ambiguous');
  assert.equal(report.observedCount, 2); // node IDs alone are not globally unique handles.
  assert.equal(report.matches[1].effective_visibility, 'GONE');
});

test('Compose exact text includes Text lists, EditableText and InputText; descriptions use all items', () => {
  const ui = snapshot(
    compose([
      node({config: {Text: ['First', 'Second']}}),
      node({config: {EditableText: 'Edited'}}),
      node({config: {InputText: 'Input'}}),
      node({config: {ContentDescription: ['First description', 'Second description']}}),
    ]),
  );
  for (const value of ['Second', 'Edited', 'Input']) {
    assert.equal(check({snapshot: ui, backend: 'compose', using: 'text', value}).status, 'unique');
  }
  assert.equal(check({snapshot: ui, backend: 'compose', using: 'text', value: 'Sec'}).status, 'not_found');
  assert.equal(
    check({snapshot: ui, backend: 'compose', using: 'accessibility id', value: 'Second description'}).status,
    'unique',
  );
});

test('merged or unidentified semantics never prove Compose uniqueness', () => {
  const ui = snapshot(compose([node({test_tag: 'save'})]));
  ui.roots[0].children[0].children[0].semantics_tree = 'merged';
  assert.equal(
    check({snapshot: ui, backend: 'compose', using: '-android viewtag', value: 'save'}).status,
    'indeterminate',
  );
});

test('partial/unavailable/error dumps do not prove absence or uniqueness', () => {
  for (const changes of [
    {status: 'partial'},
    {truncated: true},
    {errors: ['getter failed']},
    {status: 'unavailable', roots: []},
  ]) {
    const ui = {...snapshot(view({text: 'Save'})), ...changes};
    for (const matcher of [text('Save'), text('Missing')]) {
      assert.equal(check({snapshot: ui, matcher}).status, 'indeterminate');
    }
  }
  const ui = snapshot(view({text: 'Save'}));
  ui.roots[0].children[0].truncated = true;
  assert.equal(check({snapshot: ui, matcher: text('Save')}).status, 'indeterminate');
  assert.equal(check({snapshot: {status: 'captured', roots: []}, matcher: text('Save')}).status, 'indeterminate');
});

test('multiple known matches remain evidence of ambiguity in a truncated single window', () => {
  const ui = {...snapshot(view({text: 'Save'}), view({text: 'Save'})), truncated: true};
  const report = check({snapshot: ui, matcher: text('Save')});
  assert.equal(report.status, 'ambiguous');
  assert.equal(report.exact, false);
});

test('supports property conjunctions, negation and selected string Hamcrest matchers', () => {
  const ui = snapshot(view({text: 'Save draft', enabled: false}), view({text: 'Save file'}));
  const matcher = {
    name: 'allOf',
    args: [
      {class: 'LlmViewMatchers', name: 'withProperty', args: ['text', {name: 'startsWith', args: ['Save']}]},
      {name: 'not', args: [property('enabled', false)]},
    ],
  };
  assert.equal(check({snapshot: ui, matcher}).status, 'unique');
  assert.equal(
    check({snapshot: ui, matcher: {name: 'anyOf', args: [text('Save draft'), text('Save file')]}}).status,
    'ambiguous',
  );
});

test('supports native parent/ancestor/descendant/sibling constraints excluding synthetic semantics', () => {
  const ui = snapshot(
    view({resourceId: 'app:id/form', children: [view({text: 'Label'}), view({text: 'Save'})]}),
    view({text: 'Save'}),
    compose([node({test_tag: 'not-a-view', config: {Text: ['Save']}})]),
  );
  for (const relation of ['withParent', 'isDescendantOfA']) {
    const matcher = {
      name: 'allOf',
      args: [text('Save'), {class: 'ViewMatchers', name: relation, args: [property('resourceId', 'app:id/form')]}],
    };
    assert.equal(check({snapshot: ui, matcher}).status, 'unique');
  }
  const sibling = {
    name: 'allOf',
    args: [text('Save'), {class: 'ViewMatchers', name: 'hasSibling', args: [text('Label')]}],
  };
  assert.equal(check({snapshot: ui, matcher: sibling}).status, 'unique');
  assert.equal(
    check({snapshot: ui, matcher: {class: 'ViewMatchers', name: 'hasDescendant', args: [text('Label')]}}).observedCount,
    2,
  );
});

test('negation of an unknown descendant condition is not considered a definite match', () => {
  const ui = snapshot(view({truncated: true}));
  const matcher = {name: 'not', args: [{class: 'ViewMatchers', name: 'hasDescendant', args: [text('Save')]}]};
  const report = check({snapshot: ui, matcher});
  assert.equal(report.status, 'indeterminate');
  assert.equal(report.observedCount, 0);
});

test('requires explicit window scope when Android root selection is ambiguous', () => {
  const ui = dump([root([view({text: 'Save'})]), root([view({text: 'Save'})], 'dialog')]);
  assert.equal(check({snapshot: ui, matcher: text('Save')}).status, 'indeterminate');
  const matcher = {
    ...text('Save'),
    scope: {class: 'LlmRootMatchers', name: 'propertyEquals', args: ['is_dialog', true]},
  };
  const report = check({snapshot: ui, matcher});
  assert.equal(report.status, 'unique');
  assert.equal(report.matches[0].window.category, 'dialog');
});

test('unknown factories/classes/fields/numeric Hamcrest and class hierarchies are explicit unsupported cases', () => {
  const ui = snapshot(view({text: 'Save', alpha: 0.5}));
  for (const matcher of [
    {class: 'custom.ViewMatchers', name: 'withText', args: ['Save']},
    {class: 'ViewMatchers', name: 'isAssignableFrom', args: ['android.view.View']},
    property('typo', true),
    {class: 'LlmViewMatchers', name: 'withProperty', args: ['alpha', {name: 'equalTo', args: [0.5]}]},
    {class: 'ViewMatchers', name: 'withText', args: [123]},
    {class: 'ViewMatchers', name: 'withClassName', args: ['android.widget.TextView']},
    {class: 'LlmViewMatchers', name: 'withProperty', args: ['text', 'Save']},
    {class: 'ViewMatchers', name: 'withHint', args: ['Save']},
    {class: 'ViewMatchers', name: 'hasFocus', args: []},
    {class: 'ViewMatchers', name: 'withContentDescription', args: [{name: 'equalTo', args: ['Save']}]},
  ]) {
    assert.equal(check({snapshot: ui, matcher}).status, 'unsupported');
  }
  for (const using of ['xpath', 'class name']) {
    assert.equal(check({snapshot: ui, using, value: 'selector'}).status, 'unsupported');
  }
  assert.equal(check({snapshot: ui, using: 'id', value: 'bare_id'}).status, 'unsupported');
});

test('withText includes transformed text and old dumps cannot prove a negative raw-text match', () => {
  const ui = snapshot(view({text: 'save', transformed_text: 'SAVE'}));
  assert.equal(check({snapshot: ui, matcher: text('SAVE')}).status, 'unique');
  assert.equal(check({snapshot: ui, matcher: property('text', 'SAVE')}).status, 'not_found');
  delete ui.roots[0].children[0].transformed_text;
  assert.equal(check({snapshot: ui, matcher: text('SAVE')}).status, 'indeterminate');
});

test('safe scalar equality preserves types, null values and numeric equality', () => {
  const ui = snapshot(view({tag: 1, alpha: 0.5, hint: null}));
  for (const matcher of [property('tag', 1.0), property('alpha', 0.5), property('hint', null)]) {
    assert.equal(check({snapshot: ui, matcher}).status, 'unique');
  }
  assert.equal(check({snapshot: ui, matcher: property('tag', '1')}).status, 'not_found');
  assert.equal(check({snapshot: ui, matcher: property('tag', Number.MAX_SAFE_INTEGER + 1)}).status, 'unsupported');
});

test('old snapshots lacking accessibility getters are uncertain; absent type-specific properties do not match null', () => {
  const ui = snapshot(view({text: 'Save'}));
  delete ui.roots[0].children[0].contentDescription;
  assert.equal(check({snapshot: ui, using: 'accessibility id', value: 'Submit'}).status, 'indeterminate');
  assert.equal(check({snapshot: ui, matcher: property('hint', null)}).status, 'not_found');
});

test('View tags do not confuse stringified objects with string tags', () => {
  const ui = snapshot(view({tag: 'same', tag_is_stringified: true}), view({tag: 'same'}));
  assert.equal(check({snapshot: ui, using: '-android viewtag', value: 'same'}).status, 'unique');
});

test('saved snapshot checks preserve the input and malformed snapshots fail conservatively', () => {
  const ui = snapshot(view({text: 'Save'}));
  const original = JSON.stringify(ui);
  check({snapshot: ui, matcher: text('Save')});
  assert.equal(JSON.stringify(ui), original);
  assert.equal(check({snapshot: {roots: null}, matcher: text('Save')}).status, 'indeterminate');
  assert.equal(check({snapshot: snapshot({type: 'Unexpected'}), matcher: text('Save')}).status, 'indeterminate');
});

test('offline CLI reports a saved dump with documented exit statuses', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'ultron-matcher-'));
  const execute = promisify(execFile);
  const script = fileURLToPath(new URL('../bin/check-matcher.mjs', import.meta.url));
  try {
    const dumpPath = join(directory, 'dump.json');
    const queryPath = join(directory, 'query.json');
    await writeFile(dumpPath, JSON.stringify(snapshot(view({text: 'Save'}))));
    await writeFile(queryPath, JSON.stringify({backend: 'espresso', matcher: text('Save')}));
    assert.equal(
      JSON.parse((await execute(process.execPath, [script, '--snapshot', dumpPath, '--query', queryPath])).stdout)
        .status,
      'unique',
    );
    await writeFile(queryPath, JSON.stringify({backend: 'espresso', matcher: text('Missing')}));
    await assert.rejects(
      execute(process.execPath, [script, '--snapshot', dumpPath, '--query', queryPath]),
      (error) => error.code === 1 && JSON.parse(error.stdout).status === 'not_found',
    );
  } finally {
    await rm(directory, {recursive: true, force: true});
  }
});

test('RecyclerView subclasses and adapter metadata are matched without guessing from class names', () => {
  const recycler = {
    class: 'ViewMatchers',
    name: 'isAssignableFrom',
    args: ['androidx.recyclerview.widget.RecyclerView'],
  };
  const ui = dump([
    {
      ...root([view({is_recycler_view: false, recycler_position: 60, recycler_item_id: '9007199254741053'})]),
      className: 'custom.FeedView',
      is_recycler_view: true,
      recycler_item_count: 80,
    },
  ]);
  const report = check({snapshot: ui, matcher: recycler});
  assert.equal(report.status, 'unique');
  assert.equal(report.matches[0].className, 'custom.FeedView');
  assert.equal(report.matches[0].recycler_item_count, 80);
  assert.equal(check({snapshot: ui, matcher: property('recycler_position', 60)}).status, 'unique');
  assert.equal(check({snapshot: ui, matcher: property('recycler_item_id', '9007199254741053')}).status, 'unique');
  assert.equal(check({snapshot: ui, matcher: property('recycler_item_count', 0)}).status, 'not_found');
  const old = snapshot(view({className: 'custom.FeedView'}));
  assert.equal(check({snapshot: old, matcher: recycler}).status, 'indeterminate');
  assert.equal(check({snapshot: old, matcher: property('recycler_position', 60)}).status, 'indeterminate');
});
