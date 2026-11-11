import {z} from 'zod';

const states = [
  'exists',
  'doesNotExist',
  'isDisplayed',
  'isNotDisplayed',
  'isEnabled',
  'isNotEnabled',
  'isFocused',
  'isNotFocused',
  'isSelected',
  'isNotSelected',
  'isSelectable',
  'isToggleable',
  'isOn',
  'isOff',
  'isIndeterminate',
  'hasClickAction',
  'hasNoClickAction',
];
const sizes = ['widthIsAtLeast', 'widthIsEqualTo', 'heightIsAtLeast', 'heightIsEqualTo'];
const textActions = ['inputText', 'typeText', 'replaceText', 'setText'];
const contains = ['textContains', 'contentDescriptionContains'];
const swipes = ['swipe', 'swipeUp', 'swipeDown', 'swipeLeft', 'swipeRight'];
const positions = [
  'center',
  'topLeft',
  'topCenter',
  'topRight',
  'centerLeft',
  'centerRight',
  'bottomLeft',
  'bottomCenter',
  'bottomRight',
];
const scalarMatchers = [
  'isRoot',
  'isEnabled',
  'isNotEnabled',
  'isFocused',
  'isNotFocused',
  'isSelected',
  'isNotSelected',
  'isSelectable',
  'isToggleable',
  'isOn',
  'isOff',
  'hasClickAction',
  'hasScrollAction',
  'hasSetTextAction',
];
const relations = ['not', 'hasParent', 'hasAnyAncestor', 'hasAnyDescendant', 'hasAnySibling'];
const matcherNames = [
  'hasTestTag',
  'hasText',
  'hasContentDescription',
  'allOf',
  'anyOf',
  'propertyEquals',
  'hasAction',
  ...relations,
  ...scalarMatchers,
];

function validateMatcher(value, ctx) {
  let count = 0;
  function visit(node, depth = 0) {
    if (depth > 16 || ++count > 128) {
      throw new Error('Matcher exceeds depth 16 or 128 nodes');
    }
    if (
      !node ||
      typeof node !== 'object' ||
      Array.isArray(node) ||
      Object.keys(node).some((k) => !['name', 'args'].includes(k))
    ) {
      throw new Error('Compose matchers accept only name and args');
    }
    if (!matcherNames.includes(node.name)) {
      throw new Error(`Unsupported Compose matcher: ${node.name}`);
    }
    const args = node.args === undefined ? [] : node.args;
    if (!Array.isArray(args)) {
      throw new Error('Matcher args must be an array');
    }
    const arity = (min, max = min) => {
      if (args.length < min || args.length > max) {
        throw new Error(`${node.name} expects ${min}..${max} arguments`);
      }
    };
    if (scalarMatchers.includes(node.name)) {
      arity(0);
    } else if (relations.includes(node.name)) {
      arity(1);
      visit(args[0], depth + 1);
    } else if (['allOf', 'anyOf'].includes(node.name)) {
      arity(1, 32);
      args.forEach((child) => visit(child, depth + 1));
    } else {
      arity(
        node.name === 'propertyEquals' ? 2 : 1,
        ['hasText', 'hasContentDescription'].includes(node.name) ? 3 : node.name === 'propertyEquals' ? 2 : 1,
      );
      if (typeof args[0] !== 'string') {
        throw new Error('First matcher argument must be a string');
      }
      if (
        ['hasText', 'hasContentDescription'].includes(node.name) &&
        args.slice(1).some((x) => typeof x !== 'boolean')
      ) {
        throw new Error('substring and ignoreCase matcher arguments must be booleans');
      }
    }
  }
  try {
    visit(value);
  } catch (error) {
    ctx.addIssue({code: 'custom', message: error.message});
  }
}

export const composeMatcher = z
  .object({name: z.enum(matcherNames), args: z.array(z.json()).optional()})
  .strict()
  .superRefine(validateMatcher)
  .describe(
    'Compose semantics matcher {name,args?}. hasTestTag:[tag]; hasText/hasContentDescription:[text,substring?,ignoreCase?]; allOf/anyOf:[matchers]; not/hasParent/hasAnyAncestor/hasAnyDescendant/hasAnySibling:[matcher]; propertyEquals:[semanticsPropertyName,JSONValue]; hasAction:[actionName]. Depth <=16, nodes <=128. No Espresso class/scope.',
  );

const int = z.number().int().min(-2147483648).max(2147483647);
const float = z.number().min(-3.4028234663852886e38).max(3.4028234663852886e38);
const options = z
  .object({
    position: z.enum(positions).optional().describe('click/longClick/doubleClick; default center.'),
    offsetX: int.optional(),
    offsetY: int.optional(),
    durationMs: z.number().int().min(1).max(60000).optional(),
    delayMs: z
      .number()
      .int()
      .min(1)
      .max(60000)
      .optional()
      .describe('doubleClick delay; omission uses the Compose default.'),
    startXOffset: float.optional(),
    endXOffset: float.optional(),
    startYOffset: float.optional(),
    endYOffset: float.optional(),
    index: int.min(0).optional().describe('scrollToIndex: zero-based container index.'),
    key: z.union([z.string(), int]).optional(),
    keyType: z
      .enum(['string', 'int', 'long'])
      .optional()
      .describe('scrollToKey: default string; long requires a decimal string to preserve precision.'),
    value: float.optional().describe('setProgress: value in the component range, not necessarily 0..1.'),
    label: z.string().min(1).optional().describe('Exact custom accessibility action label.'),
    start: int.min(0).optional(),
    end: int.min(0).optional(),
    traversalMode: z.boolean().optional(),
    keyCode: int.min(1).optional(),
    metaState: int.min(0).optional(),
    keyAction: z
      .enum(['down', 'up'])
      .optional()
      .describe('pressKey sends one Android key event; default down. Send up separately when needed.'),
    substring: z.boolean().optional(),
    ignoreCase: z.boolean().optional(),
    includeEditableText: z.boolean().optional(),
    current: float.optional(),
    min: float.optional(),
    max: float.optional(),
    steps: int.min(0).optional(),
    dp: float.min(0).optional(),
  })
  .strict();

export function composeParameters(assertion, session) {
  return z
    .object({
      ...session,
      matcher: composeMatcher,
      ...(assertion
        ? {
            assertion: z
              .enum([
                ...states,
                'textEquals',
                ...contains,
                'contentDescriptionEquals',
                'valueEquals',
                'rangeInfoEquals',
                ...sizes,
                'matches',
              ])
              .optional(),
            expected: z
              .array(z.string())
              .optional()
              .describe(
                'Required for textEquals/contentDescriptionEquals. Preserves multiple merged values; [] asserts no values.',
              ),
            condition: composeMatcher.optional().describe('Required only for assertion=matches.'),
          }
        : {
            action: z.enum([
              'click',
              'longClick',
              'doubleClick',
              ...textActions,
              'clearText',
              'imeAction',
              'scrollTo',
              'scrollToIndex',
              'scrollToKey',
              'scrollToNode',
              ...swipes,
              'expand',
              'collapse',
              'dismiss',
              'setProgress',
              'customAccessibilityAction',
              'selectText',
              'setSelection',
              'copyText',
              'cutText',
              'pasteText',
              'pressKey',
            ]),
            itemMatcher: composeMatcher
              .optional()
              .describe('Required only for scrollToNode. Matches a descendant while the selected container scrolls.'),
          }),
      text: z.string().optional(),
      options: options.optional(),
      useUnmergedTree: z
        .boolean()
        .optional()
        .describe('Default true. The same tree is used for matching and the post-operation snapshot.'),
      timeoutMs: z.number().int().min(0).max(60000).optional(),
      name: z.string().min(1).optional(),
    })
    .strict()
    .superRefine((p, ctx) => {
      const op = assertion ? (p.assertion ?? 'exists') : p.action;
      const o = p.options ?? {};
      const invalid = (message) => ctx.addIssue({code: 'custom', message});
      let allowed = [];
      if (assertion) {
        if (op === 'textEquals') {
          allowed = ['includeEditableText'];
        } else if (contains.includes(op)) {
          allowed = ['substring', 'ignoreCase'];
        } else if (op === 'rangeInfoEquals') {
          allowed = ['current', 'min', 'max', 'steps'];
        } else if (sizes.includes(op)) {
          allowed = ['dp'];
        }
      } else {
        if (op === 'click') {
          allowed = ['position', 'offsetX', 'offsetY'];
        } else if (op === 'longClick') {
          allowed = ['position', 'offsetX', 'offsetY', 'durationMs'];
        } else if (op === 'doubleClick') {
          allowed = ['position', 'offsetX', 'offsetY', 'delayMs'];
        } else if (swipes.includes(op)) {
          allowed = ['startXOffset', 'endXOffset', 'startYOffset', 'endYOffset', 'durationMs'];
        } else {
          allowed =
            {
              scrollToIndex: ['index'],
              scrollToKey: ['key', 'keyType'],
              setProgress: ['value'],
              customAccessibilityAction: ['label'],
              selectText: ['start', 'end'],
              setSelection: ['start', 'end', 'traversalMode'],
              pressKey: ['keyCode', 'metaState', 'keyAction'],
            }[op] ?? [];
        }
      }
      if (Object.keys(o).some((k) => !allowed.includes(k))) {
        invalid(`options for ${op} may contain only ${allowed.join(', ')}`);
      }
      const needsText = assertion ? [...contains, 'valueEquals'].includes(op) : textActions.includes(op);
      if ((p.text !== undefined) !== needsText) {
        invalid('text is required only for text actions/contains/valueEquals');
      }
      if (assertion) {
        if ((p.expected !== undefined) !== ['textEquals', 'contentDescriptionEquals'].includes(op)) {
          invalid('expected is required only for textEquals/contentDescriptionEquals');
        }
        if ((p.condition !== undefined) !== (op === 'matches')) {
          invalid('condition is required only for matches');
        }
      } else if ((p.itemMatcher !== undefined) !== (op === 'scrollToNode')) {
        invalid('itemMatcher is required only for scrollToNode');
      }
      const required =
        {
          scrollToIndex: ['index'],
          scrollToKey: ['key'],
          setProgress: ['value'],
          customAccessibilityAction: ['label'],
          selectText: ['start', 'end'],
          setSelection: ['start', 'end', 'traversalMode'],
          pressKey: ['keyCode'],
          rangeInfoEquals: ['current', 'min', 'max'],
          ...Object.fromEntries(sizes.map((s) => [s, ['dp']])),
        }[op] ?? [];
      for (const key of required) {
        if (o[key] === undefined) {
          invalid(`options.${key} is required for ${op}`);
        }
      }
      if (op === 'rangeInfoEquals' && (o.min > o.max || o.current < o.min || o.current > o.max)) {
        invalid('Expected min <= current <= max');
      }
      if (op === 'scrollToKey') {
        const type = o.keyType ?? 'string';
        if (type === 'int' ? !Number.isInteger(o.key) : typeof o.key !== 'string') {
          invalid(`key must match keyType=${type}`);
        }
        if (type === 'long' && typeof o.key === 'string') {
          if (!/^-?\d+$/.test(o.key) || BigInt(o.key) < -9223372036854775808n || BigInt(o.key) > 9223372036854775807n) {
            invalid('Long key must be a decimal string in Long range');
          }
        }
      }
    });
}
