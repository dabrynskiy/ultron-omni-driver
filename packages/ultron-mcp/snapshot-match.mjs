// A deliberately bounded evaluator of dump data, not a replacement for Android's live matchers.
import {UNKNOWN, Unsupported, sameContext, snapshotLocator} from './snapshot-locators.mjs';
const MISSING = Symbol('missing');
const and = (values) => (values.includes(false) ? false : values.includes(UNKNOWN) ? UNKNOWN : true);
const or = (values) => (values.includes(true) ? true : values.includes(UNKNOWN) ? UNKNOWN : false);
const not = (value) => (value === UNKNOWN ? UNKNOWN : !value);
const scalar = (value) => value === null || ['string', 'boolean', 'number'].includes(typeof value);
const unsafe = (value) =>
  typeof value === 'number' && (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value)));
const viewProperties = new Set(
  'type source className viewId resourceId contentDescription visibility effective_visibility displayed alpha enabled focused focusable has_focusable clickable is_selected layoutParams layout_params_class tag tag_type tag_is_stringified bounds bounds.l bounds.t bounds.r bounds.b visible_bounds visible_bounds.l visible_bounds.t visible_bounds.r visible_bounds.b width height child_index child_count text transformed_text error_text hint input_type ime_target has_links is_checked scrollable scroll_x scroll_y'.split(
    ' ',
  ),
);
const recyclerProperties = [
  'is_recycler_view',
  'recycler_item_count',
  'recycler_layout_manager',
  'recycler_has_stable_ids',
  'recycler_position',
  'recycler_binding_position',
  'recycler_item_view_type',
  'recycler_item_id',
];
recyclerProperties.forEach((property) => viewProperties.add(property));
const rootProperties = new Set('type category is_dialog is_popup is_toast is_overlay root_class'.split(' '));

function field(entry, property) {
  if (entry.node.errors?.length || entry.node.type === 'Error') {
    return UNKNOWN;
  }
  let value = entry.node;
  for (const key of property.split('.')) {
    if (!value || typeof value !== 'object' || !Object.hasOwn(value, key)) {
      return MISSING;
    }
    value = value[key];
  }
  return unsafe(value) ? UNKNOWN : value;
}

function scalarMatcher(spec, depth = 0) {
  if (depth > 50) {
    throw new Unsupported('Matcher nesting exceeds 50 levels');
  }
  if (typeof spec === 'string' || typeof spec === 'boolean') {
    return (actual) => actual === spec;
  }
  if (!spec || typeof spec !== 'object') {
    throw new Unsupported('Expected a supported scalar Hamcrest matcher');
  }
  const {name, args = [], class: cls} = spec;
  if (depth && spec.scope !== undefined) {
    throw new Unsupported('Window scope is supported only on the outer matcher');
  }
  if (cls && !['Matchers', 'CoreMatchers', 'org.hamcrest.Matchers', 'org.hamcrest.CoreMatchers'].includes(cls)) {
    throw new Unsupported(`Unsupported scalar matcher class: ${cls}`);
  }
  if (!Array.isArray(args)) {
    throw new Unsupported('Matcher args must be an array');
  }
  if (['allOf', 'anyOf'].includes(name)) {
    const children = args.map((arg) => scalarMatcher(arg, depth + 1));
    return (actual) => (name === 'allOf' ? and : or)(children.map((match) => match(actual)));
  }
  if (['not', 'is'].includes(name) && args.length === 1) {
    const child = scalarMatcher(args[0], depth + 1);
    return (actual) => (name === 'not' ? not(child(actual)) : child(actual));
  }
  if (name === 'equalTo' && args.length === 1 && ['string', 'boolean'].includes(typeof args[0])) {
    return (actual) => actual === args[0];
  }
  if (['containsString', 'startsWith', 'endsWith'].includes(name) && args.length === 1 && typeof args[0] === 'string') {
    const method = {containsString: 'includes', startsWith: 'startsWith', endsWith: 'endsWith'}[name];
    return (actual) => typeof actual === 'string' && actual[method](args[0]);
  }
  // In particular, Hamcrest numeric comparisons are JVM-type-sensitive; JSON loses that type.
  throw new Unsupported(
    `Unsupported scalar matcher: ${name}. Use LlmViewMatchers.propertyEquals for numeric equality.`,
  );
}

function compile(spec, root = false, depth = 0) {
  if (depth > 50 || !spec || typeof spec !== 'object' || Array.isArray(spec)) {
    throw new Unsupported('Invalid or overly nested matcher');
  }
  const {name, args = [], class: cls} = spec;
  if (depth && spec.scope !== undefined) {
    throw new Unsupported('Window scope is supported only on the outer matcher');
  }
  if (!Array.isArray(args)) {
    throw new Unsupported('Matcher args must be an array');
  }
  const propertyClass = root ? 'LlmRootMatchers' : 'LlmViewMatchers';
  const propertyClasses = [propertyClass, `io.appium.espressoserver.lib.llm.${propertyClass}`];
  const viewClasses = ['ViewMatchers', 'androidx.test.espresso.matcher.ViewMatchers'];
  const hamcrestClasses = ['Matchers', 'CoreMatchers', 'org.hamcrest.Matchers', 'org.hamcrest.CoreMatchers'];
  if (cls && ![...propertyClasses, ...hamcrestClasses, ...(!root ? viewClasses : [])].includes(cls)) {
    throw new Unsupported(`Unsupported matcher class: ${cls}`);
  }
  if (
    propertyClasses.includes(cls) &&
    ['propertyEquals', 'withProperty'].includes(name) &&
    args.length === 2 &&
    typeof args[0] === 'string'
  ) {
    const [property, expected] = args;
    if (!(root ? rootProperties : viewProperties).has(property)) {
      throw new Unsupported(`Unknown dump property: ${property}`);
    }
    if (name === 'propertyEquals' && (!scalar(expected) || unsafe(expected))) {
      throw new Unsupported('propertyEquals requires a safe JSON scalar');
    }
    if (name === 'withProperty' && (!expected || typeof expected !== 'object' || Array.isArray(expected))) {
      throw new Unsupported('withProperty requires a nested Hamcrest matcher');
    }
    const test = name === 'propertyEquals' ? (actual) => actual === expected : scalarMatcher(expected);
    return (entry) => {
      const actual = field(entry, property);
      if (actual === UNKNOWN) {
        return UNKNOWN;
      }
      // The HTTP dumper serializes nulls; absent type-specific properties never match, even null.
      // Older servers did not expose contentDescription, so that missing getter is uncertain.
      if (actual === MISSING) {
        // Old dumps lack RecyclerView metadata. New dumps explicitly mark every View.
        return property === 'contentDescription' ||
          (recyclerProperties.includes(property) && !Object.hasOwn(entry.node, 'is_recycler_view'))
          ? UNKNOWN
          : false;
      }
      return test(actual);
    };
  }
  if (['allOf', 'anyOf'].includes(name) && (!cls || hamcrestClasses.includes(cls))) {
    const children = args.map((arg) => compile(arg, root, depth + 1));
    return (entry) => (name === 'allOf' ? and : or)(children.map((match) => match(entry)));
  }
  if (['not', 'is'].includes(name) && args.length === 1 && (!cls || hamcrestClasses.includes(cls))) {
    const child = compile(args[0], root, depth + 1);
    return (entry) => (name === 'not' ? not(child(entry)) : child(entry));
  }
  if (root) {
    throw new Unsupported(`Unsupported root matcher: ${name}`);
  }
  if (cls && !viewClasses.includes(cls)) {
    throw new Unsupported(`Unsupported View matcher: ${name}`);
  }
  if (
    name === 'isAssignableFrom' &&
    args.length === 1 &&
    ['androidx.recyclerview.widget.RecyclerView', 'androidx.recyclerview.widget.RecyclerView.class'].includes(args[0])
  ) {
    return compile({class: 'LlmViewMatchers', name: 'propertyEquals', args: ['is_recycler_view', true]});
  }
  const properties = {withText: 'text', withContentDescription: 'contentDescription', withClassName: 'className'};
  if (Object.hasOwn(properties, name) && args.length === 1) {
    if (typeof args[0] !== 'string' && (typeof args[0] !== 'object' || args[0] === null)) {
      throw new Unsupported(`${name} requires a string or supported string matcher`);
    }
    if (name === 'withClassName' && typeof args[0] === 'string') {
      throw new Unsupported('withClassName requires a nested string matcher, e.g. equalTo');
    }
    if (name === 'withContentDescription' && typeof args[0] !== 'string') {
      throw new Unsupported(
        'CharSequence matcher types are absent from the dump. Use withContentDescription(string) or propertyEquals.',
      );
    }
    const test = scalarMatcher(args[0]);
    return (entry) => {
      const actual = field(entry, properties[name]);
      if (actual === UNKNOWN) {
        return UNKNOWN;
      }
      if (actual === MISSING) {
        return name === 'withContentDescription' ? UNKNOWN : false;
      }
      if (name === 'withText' && !test(actual)) {
        const transformed = field(entry, 'transformed_text');
        return transformed === MISSING || transformed === UNKNOWN ? UNKNOWN : test(transformed);
      }
      return test(actual);
    };
  }
  if (name === 'withId' && args.length === 1 && Number.isSafeInteger(args[0])) {
    return compile({class: 'LlmViewMatchers', name: 'propertyEquals', args: ['viewId', args[0]]});
  }
  if (name === 'withEffectiveVisibility' && args.length === 1 && ['VISIBLE', 'INVISIBLE', 'GONE'].includes(args[0])) {
    return compile({class: 'LlmViewMatchers', name: 'propertyEquals', args: ['effective_visibility', args[0]]});
  }
  const booleans = {
    isDisplayed: 'displayed',
    isEnabled: 'enabled',
    isSelected: 'is_selected',
    isChecked: 'is_checked',
    isFocusable: 'focusable',
    isFocused: 'focused',
    isClickable: 'clickable',
  };
  if (Object.hasOwn(booleans, name) && args.length === 0) {
    return compile({class: 'LlmViewMatchers', name: 'propertyEquals', args: [booleans[name], true]});
  }
  if (['withParent', 'hasDescendant', 'isDescendantOfA', 'hasSibling'].includes(name) && args.length === 1) {
    const child = compile(args[0], false, depth + 1);
    return (entry) => {
      if (name === 'withParent') {
        return entry.parent?.node.type === 'View' ? child(entry.parent) : false;
      }
      const candidates = [];
      let incomplete = false;
      if (name === 'isDescendantOfA') {
        for (let parent = entry.parent; parent?.node.type === 'View'; parent = parent.parent) {
          candidates.push(parent);
        }
      } else if (name === 'hasSibling') {
        candidates.push(
          ...(entry.parent?.children ?? []).filter((item) => item !== entry && item.node.type === 'View'),
        );
        incomplete = !!entry.parent?.node.truncated;
      } else {
        const walk = (current) => {
          incomplete ||= !!current.node.truncated || !!current.node.errors?.length;
          for (const item of current.children) {
            if (item.node.type === 'View') {
              candidates.push(item);
              walk(item);
            } else if (item.node.type === 'Error') {
              incomplete = true;
            }
          }
        };
        walk(entry);
      }
      return or([...candidates.map(child), ...(incomplete ? [UNKNOWN] : [])]);
    };
  }
  throw new Unsupported(`Unsupported View matcher: ${name}`);
}

function selector(backend, using, value) {
  if (typeof using !== 'string' || typeof value !== 'string') {
    throw new Unsupported('using and value are required for a locator');
  }
  if (backend === 'compose') {
    if (['-android viewtag', 'tag name'].includes(using)) {
      return (entry) => field(entry, 'test_tag') === value;
    }
    if (['text', 'link text', 'accessibility id'].includes(using)) {
      return (entry) => {
        if (entry.node.errors?.length) {
          return UNKNOWN;
        }
        const config = entry.node.config;
        if (!config || typeof config !== 'object') {
          return UNKNOWN;
        }
        if (using === 'accessibility id') {
          return config.ContentDescription === undefined
            ? false
            : Array.isArray(config.ContentDescription)
              ? config.ContentDescription.includes(value)
              : UNKNOWN;
        }
        // Mirrors Compose hasText: Text, EditableText and InputText, exact and case-sensitive.
        if (
          (config.Text !== undefined && !Array.isArray(config.Text)) ||
          ['EditableText', 'InputText'].some((key) => config[key] !== undefined && typeof config[key] !== 'string')
        ) {
          return UNKNOWN;
        }
        return (config.Text ?? []).includes(value) || config.EditableText === value || config.InputText === value;
      };
    }
    throw new Unsupported(`Unsupported Compose locator: ${using}`);
  }
  if (using === '-android viewmatcher') {
    let matcher;
    try {
      matcher = JSON.parse(value);
    } catch {
      throw new Unsupported('Invalid JSON View matcher');
    }
    return {match: compile(matcher), scope: matcher.scope};
  }
  if (using === 'id' && /^[\w.]+:id\/.+$/.test(value)) {
    return compile({class: 'LlmViewMatchers', name: 'propertyEquals', args: ['resourceId', value]});
  }
  if (using === 'text') {
    return compile({class: 'ViewMatchers', name: 'withText', args: [value]});
  }
  if (using === 'accessibility id') {
    return compile({class: 'ViewMatchers', name: 'withContentDescription', args: [value]});
  }
  if (['-android viewtag', 'tag name'].includes(using)) {
    return (entry) => {
      const tag = field(entry, 'tag');
      if (tag === UNKNOWN || (typeof tag === 'string' && entry.node.tag_is_stringified === undefined)) {
        return UNKNOWN;
      }
      return entry.node.tag_is_stringified ? false : tag === value;
    };
  }
  // Bare IDs need AUT resource lookup; class name uses instanceof, whose hierarchy is absent.
  throw new Unsupported(
    `Unsupported View locator: ${using}. Use an absolute resource ID, text, accessibility id, tag or supported JSON matcher.`,
  );
}

export function checkSnapshotMatcher({snapshot, backend = 'espresso', matcher, using, value, scope, limit = 20}) {
  const report = {
    backend,
    basis: 'snapshot',
    snapshotId: snapshot?.snapshotId ?? null,
    capturedAtMs: snapshot?.capturedAtMs ?? null,
    status: 'indeterminate',
    observedCount: 0,
    exact: false,
    matches: [],
    matchesTruncated: false,
    reasons: [],
    runtimeVerificationRequired: true,
  };
  const reasons = new Set();
  try {
    const accessibility = snapshot?.backend === 'automator' || snapshot?.mode === 'automator';
    const dumpLocator = ['automator', 'dom'].includes(backend);
    if (accessibility && !dumpLocator) {
      throw new Unsupported('Accessibility snapshots cannot verify Espresso/Compose matchers; request mode=ultron.');
    }
    if (!['espresso', 'compose', 'automator', 'dom'].includes(backend)) {
      throw new Unsupported('backend must be espresso, compose, automator or dom');
    }
    if (backend === 'automator' && !accessibility) {
      throw new Unsupported('UIAutomator locators require an accessibility snapshot; request mode=automator.');
    }
    if (dumpLocator && matcher) {
      throw new Unsupported('UIAutomator/DOM require using/value, not an Espresso matcher');
    }
    if (scope !== undefined) {
      if (
        !dumpLocator ||
        !scope ||
        typeof scope !== 'object' ||
        Array.isArray(scope) ||
        Object.keys(scope).some(
          (key) => !['windowId', ...(backend === 'dom' ? ['webview', 'context'] : [])].includes(key),
        ) ||
        (scope.windowId !== undefined && !Number.isSafeInteger(scope.windowId)) ||
        (scope.webview !== undefined &&
          (typeof scope.webview !== 'string' || !/^\/roots\/\d+(\/children\/\d+)*$/.test(scope.webview))) ||
        (scope.context !== undefined &&
          (!Array.isArray(scope.context) ||
            scope.context.some(
              (item) =>
                !item ||
                !['frame', 'shadow'].includes(item.type) ||
                typeof item.selector !== 'string' ||
                Object.keys(item).some((key) => !['type', 'selector'].includes(key)),
            )))
      ) {
        throw new Unsupported('Invalid locator scope; use windowId for automator, windowId/webview/context for dom');
      }
    }
    if (backend === 'compose' && matcher) {
      throw new Unsupported('Compose requires using/value, matching the driver locator API');
    }
    if (matcher && using !== undefined) {
      throw new Unsupported('Provide matcher or using/value, not both');
    }
    const compiled = dumpLocator
      ? snapshotLocator(backend, using, value)
      : matcher
        ? {match: compile(matcher), scope: matcher.scope}
        : selector(backend, using, value);
    const match = typeof compiled === 'function' ? compiled : compiled.match;
    const rootScope = compiled.scope ? compile(compiled.scope, true) : null;
    if (!snapshot || !Array.isArray(snapshot.roots)) {
      reasons.add('Snapshot roots are unavailable');
    }
    let complete =
      snapshot?.status === 'captured' &&
      snapshot.truncated === false &&
      Array.isArray(snapshot.errors) &&
      !snapshot.errors.length;
    if (!complete) {
      reasons.add('Snapshot is partial, truncated, unavailable, or missing completeness metadata');
    }
    const entries = [];
    const roots = [];
    const visit = (node, parent, root, path, semanticsRoot) => {
      if (entries.length >= 10000 || path.split('/children/').length > 101) {
        throw new Error('Snapshot exceeds the supported 10000-node/100-depth traversal budget');
      }
      if (!node || typeof node !== 'object') {
        complete = false;
        reasons.add('Malformed node');
        return;
      }
      const entry = {node, parent, root, path, children: [], semanticsRoot};
      entry.webview = node.type === 'DomNode' ? (parent?.node.type === 'DomNode' ? parent.webview : parent) : null;
      if (!root) {
        entry.root = entry;
        roots.push(entry);
      }
      if (node.semantics_root === true) {
        entry.semanticsRoot = entry;
      }
      entries.push(entry);
      parent?.children.push(entry);
      if (node.truncated || node.errors?.length || node.type === 'Error') {
        complete = false;
        reasons.add('A node is truncated or has collection errors');
      }
      if (!['View', 'SemanticsNode', 'AccessibilityNode', 'DomNode', 'Error'].includes(node.type)) {
        complete = false;
        reasons.add('Unknown node type');
      }
      if (
        backend === 'dom' &&
        ((node.dom_status && node.dom_status !== 'captured') ||
          (node.type === 'DomNode' &&
            (!entry.webview || !Array.isArray(node.context) || !sameContext(node.context, node.context))))
      ) {
        complete = false;
        reasons.add('DOM collection or document context is unavailable');
      }
      if (node.children !== undefined && !Array.isArray(node.children)) {
        complete = false;
        reasons.add('Malformed children');
        return;
      }
      for (const [index, child] of (node.children ?? []).entries()) {
        visit(child, entry, entry.root, `${path}/children/${index}`, entry.semanticsRoot);
      }
    };
    for (const [index, root] of (snapshot?.roots ?? []).entries()) {
      visit(root, null, null, `/roots/${index}`, null);
    }
    const selectedRoots = roots.filter((root) => {
      if (scope?.windowId !== undefined) {
        const id = root.node.window?.id ?? root.node.windowId;
        if (id === undefined) {
          complete = false;
          reasons.add('Window ID is unavailable');
        }
        return id === scope.windowId;
      }
      if (!rootScope) {
        return true;
      }
      const scoped = root.node.window ? rootScope({node: root.node.window}) : UNKNOWN;
      if (scoped === UNKNOWN) {
        complete = false;
        reasons.add('Window scope could not be evaluated');
      }
      return scoped === true;
    });
    if (backend === 'espresso' && selectedRoots.length > 1) {
      complete = false;
      reasons.add(
        'Multiple windows match: Espresso root selection cannot be inferred from the dump. Narrow matcher.scope.',
      );
    }
    const multiWindow = backend === 'espresso' && selectedRoots.length > 1;
    const matches = [];
    let unknown = 0;
    for (const entry of entries) {
      if (
        !selectedRoots.includes(entry.root) ||
        entry.node.type !==
          {compose: 'SemanticsNode', espresso: 'View', automator: 'AccessibilityNode', dom: 'DomNode'}[backend]
      ) {
        continue;
      }
      if (
        backend === 'dom' &&
        ((scope?.webview !== undefined && scope.webview !== entry.webview?.path) ||
          !sameContext(entry.node.context, scope?.context ?? []))
      ) {
        continue;
      }
      if (backend === 'compose' && entry.semanticsRoot?.node.semantics_tree !== 'unmerged') {
        unknown++;
        reasons.add('Compose locators require an identified unmerged semantics tree');
        continue;
      }
      const evaluated = match(entry);
      if (evaluated === UNKNOWN) {
        unknown++;
        reasons.add('Some matching properties are unavailable or cannot be represented exactly in JSON');
      }
      if (evaluated === true) {
        matches.push(entry);
      }
    }
    report.observedCount = matches.length;
    report.exact = complete && unknown === 0;
    report.status =
      !multiWindow && matches.length > 1
        ? 'ambiguous'
        : !report.exact
          ? 'indeterminate'
          : matches.length === 1
            ? 'unique'
            : 'not_found';
    report.matches = matches.slice(0, Math.max(1, Math.min(100, limit))).map(({node, path, root, webview}) => ({
      path,
      window: root.node.window,
      ...(node.type === 'SemanticsNode' ? {config: node.config} : {}),
      ...(node.type === 'DomNode'
        ? {
            webview: webview?.path,
            context: node.context,
            selector: node.selector,
            tagName: node.tagName,
            attributes: node.attributes,
          }
        : {}),
      ...Object.fromEntries(
        [
          'type',
          'source',
          'className',
          'resourceId',
          'viewId',
          'test_tag',
          'node_id',
          'text',
          'contentDescription',
          'packageName',
          'windowId',
          'bounds',
          'dom_bounds',
          'displayed',
          'enabled',
          'sensitive',
          'visibility',
          'effective_visibility',
          ...recyclerProperties,
        ]
          .filter((key) => Object.hasOwn(node, key))
          .map((key) => [key, node[key]]),
      ),
    }));
    report.matchesTruncated = report.matches.length < matches.length;
  } catch (error) {
    report.status = error instanceof Unsupported ? 'unsupported' : 'indeterminate';
    reasons.add(error.message);
  }
  report.reasons = [...reasons];
  return report;
}

export async function freshMatcherCheck(driver, query) {
  const snapshot = await driver.executeScript('mobile: ultronSnapshot', [
    {
      maxDepth: 100,
      maxNodes: 10000,
      useUnmergedTree: true,
      mode: query.backend === 'automator' ? 'automator' : 'ultron',
    },
  ]);
  return {snapshot, preflight: checkSnapshotMatcher({...query, snapshot})};
}
