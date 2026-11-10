import {z} from 'zod';
import {composeParameters} from './compose.mjs';
import {checkSnapshotMatcher, freshMatcherCheck} from './snapshot-match.mjs';

const session = {
  sessionId: z.string().min(1).optional().describe('Appium MCP session ID. Omit only when the active session is unambiguous.'),
};
const matcher = z.object({
  name: z.string().min(1),
  class: z.string().optional(),
  args: z.array(z.json()).optional(),
  scope: z.record(z.string(), z.json()).optional(),
}).strict().describe('Espresso JSON matcher, e.g. {class:"LlmViewMatchers",name:"propertyEquals",args:["resourceId","com.example:id/submit"]}. Combine with allOf/withParent; select windows using scope. Matches Android Views, not Compose semantics.');
const timeout = z.number().int().min(0).max(60000).optional();
const int32 = z.number().int().min(-2147483648).max(2147483647);
const cornerClicks = ['clickTopLeft', 'clickTopRight', 'clickBottomRight', 'clickBottomLeft'];
const xOffsetClicks = [...cornerClicks, 'clickCenterLeft', 'clickCenterRight'];
const yOffsetClicks = [...cornerClicks, 'clickTopCenter', 'clickBottomCenter'];
const readOnly = {readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false};

// Use Espresso factories so visibility, focus and Checkable semantics stay authoritative on-device.
const stateAssertions = {
  isDisplayed: ['isDisplayed'], isNotDisplayed: ['isDisplayed', true],
  isCompletelyDisplayed: ['isCompletelyDisplayed'],
  isEnabled: ['isEnabled'], isNotEnabled: ['isEnabled', true],
  isSelected: ['isSelected'], isNotSelected: ['isSelected', true],
  isClickable: ['isClickable'], isNotClickable: ['isClickable', true],
  isChecked: ['isChecked'], isNotChecked: ['isNotChecked'],
  isFocusable: ['isFocusable'], isNotFocusable: ['isFocusable', true],
  hasFocus: ['hasFocus'], isJavascriptEnabled: ['isJavascriptEnabled'],
};
const exactTextAssertions = ['hasText', 'hasContentDescription'];
const containsTextAssertions = ['textContains', 'contentDescriptionContains'];
const assertionNames = ['exists', 'doesNotExist', ...Object.keys(stateAssertions), 'isVisible',
  'isDisplayingAtLeast', ...exactTextAssertions, ...containsTextAssertions];

function namedAssertionArgs({assertion, text, resourceId, percentage, ...args}) {
  if (assertion === undefined) {return args;}
  if (assertion === 'exists' || assertion === 'doesNotExist') {
    return {...args, exists: assertion === 'exists'};
  }
  const viewMatcher = (name, values) => ({class: 'ViewMatchers', name, ...(values === undefined ? {} : {args: values})});
  let condition;
  if (Object.hasOwn(stateAssertions, assertion)) {
    const [factory, negate] = stateAssertions[assertion];
    condition = viewMatcher(factory);
    if (negate) {condition = {name: 'not', args: [condition]};}
  } else if (assertion === 'isVisible') {
    condition = viewMatcher('withEffectiveVisibility', ['VISIBLE']);
  } else if (assertion === 'isDisplayingAtLeast') {
    condition = viewMatcher('isDisplayingAtLeast', [percentage]);
  } else {
    const factory = ['hasText', 'textContains'].includes(assertion) ? 'withText' : 'withContentDescription';
    const expected = containsTextAssertions.includes(assertion)
      ? {name: 'containsString', args: [text]} : text ?? resourceId;
    condition = viewMatcher(factory, [expected]);
  }
  return {...args, condition};
}

function result(value, isError = false) {
  return {
    isError,
    content: [{type: 'text', text: JSON.stringify(value)}],
    ...(value !== null && typeof value === 'object' ? {structuredContent: value} : {}),
  };
}

/** Appium MCP CLI plugin: --plugin /absolute/path/to/index.mjs. No private host imports. */
export default class UltronMcpPlugin {
  name = 'ultron-driver';
  version = '0.1.0';
  #queues = new WeakMap();

  register(registry, core) {
    const add = (definition, run, {allowOffline = false} = {}) => registry.addTool({
      ...definition,
      execute: async (rawArgs) => {
        try {
          const args = definition.parameters.parse(rawArgs);
          if (allowOffline && args.snapshot) {return await run(null, args);}
          const driver = core.getDriver(args.sessionId);
          if (!driver || typeof driver.executeScript !== 'function') {
            throw new Error('Create or attach a session first: ultron_session in standalone mode, or appium_session_management with remoteServerUrl in Appium MCP.');
          }
          // Hold the selected driver for the entire call; switching the active session cannot retarget it.
          // Serialize plugin calls, including snapshot settings, per driver without blocking other sessions.
          const previous = this.#queues.get(driver) ?? Promise.resolve();
          const pending = previous.then(() => run(driver, args));
          const settled = pending.then(() => {}, () => {});
          this.#queues.set(driver, settled);
          try { return await pending; }
          finally { if (this.#queues.get(driver) === settled) {this.#queues.delete(driver);} }
        } catch (error) {
          return result({error: error instanceof Error ? error.message : String(error)}, true);
        }
      },
    });
    const execute = (driver, command, args = {}) => driver.executeScript(`mobile: ${command}`, [args]);
    const operation = async (driver, command, {sessionId: _sessionId, preflight = false, ...args}) => {
      let checked;
      if (preflight) {
        checked = await freshMatcherCheck(driver, {backend: 'espresso', matcher: args.matcher});
        if (checked.preflight.status !== 'unique') {
          return result({operation: {status: 'not_executed', error: 'Matcher preflight did not establish one target'}, ...checked}, true);
        }
      }
      // Check current settings rather than caching across server/session resets or external clients.
      if ((await driver.getSettings()).ultronSnapshotEnabled !== true) {
        await driver.updateSettings({ultronSnapshotEnabled: true});
      }
      // Deliberately no outer retry: a lost response can follow an already completed action.
      const value = await execute(driver, command, args);
      if (!value?.operation || !['success', 'failure'].includes(value.operation.status)) {
        throw new Error('Unexpected Ultron operation response. Observe the UI before repeating an action.');
      }
      return result({...value, ...(checked ? {preflight: checked.preflight} : {})}, value.operation.status === 'failure');
    };

    add({
      name: 'ultron_check_matcher',
      description: 'Check whether a matcher/locator has exactly one match in a fresh or supplied JSON dump, without acting. backend=espresso accepts matcher or using/value; compose accepts using/value (test tag, exact text, content description) in an unmerged tree. Reports unique, not_found, ambiguous, unsupported or indeterminate with bounded candidate details. Includes hidden nodes. Partial dumps, unsupported semantics and uncertain window selection never prove uniqueness. UI can change after this observation.',
      parameters: z.object({...session, backend: z.enum(['espresso', 'compose']), matcher: matcher.optional(),
        using: z.string().optional(), value: z.string().optional(),
        snapshot: z.record(z.string(), z.json()).optional().describe('Optional saved snapshot; works offline without an Android session.'),
        limit: z.number().int().min(1).max(100).optional(),
      }).strict(),
      annotations: readOnly,
    }, async (driver, args) => {
      const report = args.snapshot ? checkSnapshotMatcher(args) : (await freshMatcherCheck(driver, args)).preflight;
      return result(report, report.status !== 'unique');
    }, {allowOffline: true});

    add({
      name: 'ultron_snapshot',
      description: 'Observe the current UI as a JSON tree of Android Views and embedded Compose semantics (composeOn server). Includes hidden subtrees and window metadata. Check status, errors and truncated before choosing a target. Does not switch the driver backend.',
      parameters: z.object({...session,
        maxDepth: z.number().int().min(0).max(100).optional(),
        maxNodes: z.number().int().min(1).max(10000).optional(),
        useUnmergedTree: z.boolean().optional().describe('Default true, matching Appium Compose locators. False returns merged semantics for this observation only.'),
      }).strict(),
      annotations: readOnly,
    }, async (driver, {sessionId: _sessionId, ...args}) => {
      const value = await execute(driver, 'ultronSnapshot', args);
      if (!value || !['captured', 'partial', 'unavailable'].includes(value.status) || !Array.isArray(value.roots)) {
        throw new Error('Unexpected Ultron snapshot response. Check that the remote server uses the Ultron-enabled driver.');
      }
      return result(value, value?.status === 'unavailable');
    });

    add({
      name: 'ultron_act',
      description: 'Perform one Espresso action on a unique Android View and return the operation plus a mixed View/Compose snapshot. Enables automatic snapshots. Compose semantics are observable but cannot be targeted by this tool. A successful action does not prove the expected test result; use ultron_assert. After transport loss, observe before retrying a mutation.',
      parameters: z.object({...session, matcher,
        action: z.enum(['click', 'doubleClick', 'longClick', ...cornerClicks, 'clickTopCenter', 'clickBottomCenter',
          'clickCenterLeft', 'clickCenterRight', 'typeText', 'replaceText', 'clearText', 'scrollTo',
          'swipeUp', 'swipeDown', 'swipeLeft', 'swipeRight', 'pressKey', 'closeSoftKeyboard']),
        text: z.string().optional().describe('Required for typeText/replaceText; empty string is valid.'),
        timeoutMs: timeout,
        offsetX: int32.optional().describe('Horizontal offset in pixels for corner and center-left/right clicks. Default 0; follows Ultron offset semantics.'),
        offsetY: int32.optional().describe('Vertical offset in pixels for corner and top/bottom-center clicks. Default 0; follows Ultron offset semantics.'),
        keyCode: int32.min(1).optional().describe('Android KEYCODE integer, required for pressKey; must be below the device max key code. The matched View must be displayed; key events go to the current focus.'),
        metaState: int32.min(0).optional().describe('Optional Android KeyEvent modifier bitmask for pressKey; default 0.'),
        flags: int32.min(0).optional().describe('Optional Android KeyEvent flags for pressKey; default 0.'),
        autoScroll: z.boolean().optional().describe('Override Ultron auto-scroll for this action only. Omit to use the configured default. Positional clicks always use Ultron custom scrolling.'),
        name: z.string().min(1).optional().describe('Element label used by Ultron in operation names and diagnostics.'),
        preflight: z.boolean().optional().describe('Default false. Require one match in a fresh complete dump before acting. If unavailable, unsupported or non-unique, no action is executed. This bypasses Ultron waiting for a future target.'),
      }).strict().superRefine((args, ctx) => {
        const invalid = (path, message) => ctx.addIssue({code: 'custom', path: [path], message});
        if (['typeText', 'replaceText'].includes(args.action) && args.text === undefined) {
          invalid('text', 'text is required for typeText and replaceText');
        }
        if (args.offsetX !== undefined && !xOffsetClicks.includes(args.action)) {
          invalid('offsetX', 'offsetX is supported by corner and center-left/right clicks only');
        }
        if (args.offsetY !== undefined && !yOffsetClicks.includes(args.action)) {
          invalid('offsetY', 'offsetY is supported by corner and top/bottom-center clicks only');
        }
        if (args.action === 'pressKey' && args.keyCode === undefined) {invalid('keyCode', 'keyCode is required for pressKey');}
        if (args.action !== 'pressKey') {
          for (const key of ['keyCode', 'metaState', 'flags']) {
            if (args[key] !== undefined) {invalid(key, `${key} is supported by pressKey only`);}
          }
        }
      }),
      annotations: {readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true},
    }, (driver, args) => operation(driver, 'ultronAct', args));

    add({
      name: 'ultron_assert',
      description: 'Verify an expected Android View state with Ultron waiting and return the assertion plus a mixed View/Compose snapshot. Use assertion for named Ultron checks (e.g. isDisplayed, isNotEnabled, hasText with text, isDisplayingAtLeast with percentage), or condition for a custom Espresso matcher, or exists for legacy existence/absence checks. Named checks cannot be combined with condition/exists. Default checks existence. Does not assert Compose semantics. Assertion failure is an MCP error and retains the snapshot as evidence.',
      parameters: z.object({...session, matcher, condition: matcher.optional(), exists: z.boolean().optional(), timeoutMs: timeout,
        assertion: z.enum(assertionNames).optional().describe('Named Ultron assertion. Use alone with its text/resourceId/percentage argument; omit for legacy condition/exists checks.'),
        text: z.string().optional().describe('Expected exact text for hasText/hasContentDescription, or substring for textContains/contentDescriptionContains. Empty string is valid.'),
        resourceId: int32.min(1).optional().describe('Android string resource ID for hasText/hasContentDescription, instead of text. Resolved on-device.'),
        percentage: z.number().int().min(0).max(100).optional().describe('Required for isDisplayingAtLeast; inclusive range 0..100.'),
      })
        .strict().refine(args => !(args.exists === false && args.condition !== undefined),
          {message: 'condition cannot be combined with exists=false', path: ['condition']})
        .refine(args => args.condition?.scope === undefined,
          {message: 'Use matcher.scope to select the window, not condition.scope', path: ['condition']})
        .superRefine((args, ctx) => {
          const invalid = (path, message) => ctx.addIssue({code: 'custom', path: [path], message});
          if (args.assertion !== undefined && (args.condition !== undefined || args.exists !== undefined)) {
            invalid('assertion', 'assertion cannot be combined with condition or exists');
          }
          const exact = exactTextAssertions.includes(args.assertion);
          const contains = containsTextAssertions.includes(args.assertion);
          if (exact && (Number(args.text !== undefined) + Number(args.resourceId !== undefined) !== 1)) {
            invalid('text', 'hasText/hasContentDescription require exactly one of text or resourceId');
          }
          if (contains && args.text === undefined) {invalid('text', 'text is required for substring assertions');}
          if (args.text !== undefined && !exact && !contains) {invalid('text', 'text requires a named text/content-description assertion');}
          if (args.resourceId !== undefined && !exact) {invalid('resourceId', 'resourceId requires hasText or hasContentDescription');}
          if (args.assertion === 'isDisplayingAtLeast' && args.percentage === undefined) {
            invalid('percentage', 'percentage is required for isDisplayingAtLeast');
          }
          if (args.percentage !== undefined && args.assertion !== 'isDisplayingAtLeast') {
            invalid('percentage', 'percentage requires isDisplayingAtLeast');
          }
        }),
      // UI is only observed, but this tool also enables snapshot collection in session settings.
      annotations: {...readOnly, readOnlyHint: false},
    }, (driver, args) => operation(driver, 'ultronAssert', namedAssertionArgs(args)));

    for (const assertion of [false, true]) {
      add({
        name: assertion ? 'ultron_compose_assert' : 'ultron_compose_act',
        description: assertion
          ? 'Verify Compose semantics using Ultron waiting and return operation plus a fresh mixed View/Compose snapshot, including failures. Default assertion=exists. Text equality accepts expected arrays; substring/ignoreCase default false. Checks run on Android, including unique target resolution. No global driver switch. Does not prove that absent Lazy items are absent from the data set.'
          : 'Perform one Compose operation on a unique semantics node, returning operation plus a fresh mixed View/Compose snapshot on success/failure. Does not switch the global driver. scrollTo targets an existing node; scrollToIndex/Key/Node target the scroll container and can find off-screen Lazy items. Actions require the corresponding component semantics. selectText uses input text indices; setSelection requires explicit traversalMode. Custom actions use an exact label. A successful invocation requires a separate assertion to verify its UI effect. After transport loss, observe before repeating a mutation.',
        parameters: composeParameters(assertion, session),
        annotations: {readOnlyHint: false, destructiveHint: !assertion, idempotentHint: assertion, openWorldHint: false},
      }, (driver, args) => operation(driver, assertion ? 'ultronComposeAssert' : 'ultronComposeAct', args));
    }

    add({
      name: 'ultron_recycler',
      description: 'Interact with a unique RecyclerView, including custom subclasses. Select the list with matcher (and matcher.scope for a window). Select an item by zero-based outer-adapter position OR itemMatcher matching the item root (use hasDescendant for row text). Searches off-screen items by binding temporary holders; use content matchers rather than screen visibility. Default searchLimit=1000; incomplete search cannot prove absence or uniqueness. Actions scroll to the item before interacting; childMatcher selects exactly one descendant within that item. Assertions cover current adapter data, including Paging placeholders, not future unloaded pages. Returns operation and post-operation snapshot; failure is an MCP error. Uses Ultron retries without an extra transport retry.',
      parameters: z.object({...session, matcher,
        action: z.enum(['scrollToItem', 'click', 'longClick', 'doubleClick', 'typeText', 'replaceText', 'clearText',
          'assertItem', 'assertItemExists', 'assertItemNotExists', 'assertItemCount']),
        position: int32.min(0).optional().describe('Zero-based absolute adapter position, including ConcatAdapter headers. Mutually exclusive with itemMatcher.'),
        itemMatcher: matcher.optional().describe('Matches item roots across current adapter data. Mutually exclusive with position. No nested window scope.'),
        childMatcher: matcher.optional().describe('Select one descendant inside the item for click, text actions or assertItem. No nested window scope.'),
        condition: matcher.optional().describe('Required only for assertItem: expected state of the item or selected child. No nested window scope.'),
        itemCount: int32.min(0).optional().describe('Required only for assertItemCount; checks adapter count, not attached child count.'),
        searchLimit: z.number().int().min(1).max(10000).optional().describe('Only with itemMatcher. Maximum adapter positions examined per Ultron attempt; default 1000.'),
        text: z.string().optional().describe('Required for typeText/replaceText; empty string is valid.'),
        timeoutMs: timeout,
        name: z.string().min(1).optional(),
        preflight: z.boolean().optional().describe('Check list uniqueness in a fresh dump before executing. Checks the list only; off-screen items are resolved on Android.'),
      }).strict().superRefine((args, ctx) => {
        const invalid = (path, message) => ctx.addIssue({code: 'custom', path: [path], message});
        if (args.action === 'assertItemCount') {
          if (args.itemCount === undefined) {invalid('itemCount', 'itemCount is required');}
          if (args.position !== undefined || args.itemMatcher !== undefined) {invalid('position', 'assertItemCount does not accept an item target');}
        } else {
          if ((args.position !== undefined) === (args.itemMatcher !== undefined)) {invalid('position', 'Provide exactly one of position or itemMatcher');}
          if (args.itemCount !== undefined) {invalid('itemCount', 'itemCount requires assertItemCount');}
        }
        if (args.searchLimit !== undefined && args.itemMatcher === undefined) {invalid('searchLimit', 'searchLimit requires itemMatcher');}
        if (args.childMatcher !== undefined && ['scrollToItem', 'assertItemExists', 'assertItemNotExists', 'assertItemCount'].includes(args.action)) {
          invalid('childMatcher', 'childMatcher requires a target action');
        }
        if ((args.condition !== undefined) !== (args.action === 'assertItem')) {invalid('condition', 'condition is required for assertItem only');}
        const textAction = ['typeText', 'replaceText'].includes(args.action);
        if (textAction && args.text === undefined) {invalid('text', 'text is required');}
        if (!textAction && args.text !== undefined) {invalid('text', 'text requires typeText or replaceText');}
        for (const key of ['itemMatcher', 'childMatcher', 'condition']) {
          if (args[key]?.scope !== undefined) {invalid(key, 'Use matcher.scope for window selection');}
        }
      }),
      // Some assertions scroll to inspect an item; the tool also enables snapshot settings.
      annotations: {readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true},
    }, (driver, args) => operation(driver, 'ultronRecycler', args));

    add({
      name: 'ultron_last_result',
      description: 'Read the last saved Espresso or Compose Ultron operation and its snapshot, or null. This is not a command journal or an idempotency guarantee; another command may have replaced it.',
      parameters: z.object(session).strict(),
      annotations: readOnly,
    }, async driver => {
      const value = await execute(driver, 'ultronLastResult');
      return result(value, value?.operation?.status === 'failure');
    });

    add({
      name: 'ultron_settings',
      description: 'Read Ultron snapshot settings, optionally enable/disable automatic snapshots or select espresso/compose for standard Appium commands. Compose requires a composeOn server. ultron_act and ultron_assert always use Espresso and automatically enable snapshots; standard Compose commands use the selected driver.',
      parameters: z.object({...session, snapshotEnabled: z.boolean().optional(), driver: z.enum(['espresso', 'compose']).optional()}).strict(),
      annotations: {readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false},
    }, async (driver, args) => {
      const settings = {};
      if (args.snapshotEnabled !== undefined) {settings.ultronSnapshotEnabled = args.snapshotEnabled;}
      if (args.driver !== undefined) {settings.driver = args.driver;}
      if (Object.keys(settings).length) {await driver.updateSettings(settings);}
      return result(await driver.getSettings());
    });
  }
}
