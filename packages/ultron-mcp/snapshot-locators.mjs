// Bounded, read-only locator evaluation. Never execute supplied UiSelector or page code.
export const UNKNOWN = Symbol('unknown');
export class Unsupported extends Error {}
const and = (values) => (values.includes(false) ? false : values.includes(UNKNOWN) ? UNKNOWN : true);
const or = (values) => (values.includes(true) ? true : values.includes(UNKNOWN) ? UNKNOWN : false);

function equals(property, expected) {
  return ({node}) => {
    if (node.errors?.length || (node.sensitive && ['text', 'contentDescription', 'hint', 'value'].includes(property))) {
      return UNKNOWN;
    }
    if (property === 'is_checked' && node.checkable === false) {
      return expected === false;
    }
    return Object.hasOwn(node, property) ? node[property] === expected : UNKNOWN;
  };
}

function automator(using, value) {
  const direct = {id: 'resourceId', text: 'text', 'accessibility id': 'contentDescription', 'class name': 'className'};
  if (Object.hasOwn(direct, using)) {
    return equals(direct[using], value);
  }
  if (using !== '-android uiautomator') {
    throw new Unsupported(`Unsupported accessibility locator: ${using}`);
  }
  let rest = value
    .trim()
    .replace(
      /^(?:new\s+)?(?:android\.support\.test\.uiautomator\.|androidx\.test\.uiautomator\.)?UiSelector\s*\(\s*\)/,
      '',
    );
  if (rest === value.trim()) {
    throw new Unsupported('Expected new UiSelector() followed by supported exact predicates');
  }
  const strings = {
    text: 'text',
    description: 'contentDescription',
    resourceId: 'resourceId',
    className: 'className',
    packageName: 'packageName',
  };
  const booleans = {
    enabled: 'enabled',
    focused: 'focused',
    focusable: 'focusable',
    clickable: 'clickable',
    longClickable: 'long_clickable',
    selected: 'is_selected',
    checkable: 'checkable',
    checked: 'is_checked',
    scrollable: 'scrollable',
  };
  const tests = [];
  const seen = new Set();
  while (rest.trim()) {
    // JSON string literals are a safe subset of Java strings. No eval, regex factories or nested selectors.
    const part = /^\s*\.\s*(\w+)\s*\(\s*("(?:[^"\\\r\n]|\\["\\/bfnrt]|\\u[0-9a-fA-F]{4})*"|true|false)\s*\)/.exec(rest);
    if (!part || tests.length >= 30) {
      throw new Unsupported('Unsupported UiSelector expression; use exact string/boolean predicates');
    }
    const [, method, literal] = part;
    const expected = JSON.parse(literal);
    const properties = typeof expected === 'string' ? strings : booleans;
    if (!Object.hasOwn(properties, method) || seen.has(method)) {
      throw new Unsupported(`Unsupported or repeated UiSelector predicate: ${method}`);
    }
    seen.add(method);
    tests.push(equals(properties[method], expected));
    rest = rest.slice(part[0].length);
  }
  if (!tests.length) {
    throw new Unsupported('UiSelector requires at least one predicate');
  }
  return (entry) => and(tests.map((test) => test(entry)));
}

const attributes = new Set(['id', 'name', 'type', 'role', 'aria-label', 'placeholder']);
const asciiLower = (value) => value.replace(/[A-Z]/g, (letter) => letter.toLowerCase());
const identifier =
  /^(?:--|-?(?:[a-zA-Z_\u0080-\uffff]|\\(?:[0-9a-fA-F]{1,6}\s?|[^\r\n\f])))(?:[\w\u0080-\uffff-]|\\(?:[0-9a-fA-F]{1,6}\s?|[^\r\n\f]))*/;
function unescapeCss(value) {
  return value.replace(/\\([0-9a-fA-F]{1,6}\s?|[^\r\n\f])/g, (_, escaped) => {
    if (!/^[0-9a-fA-F]/.test(escaped)) {
      return escaped;
    }
    const code = Number.parseInt(escaped.trim(), 16);
    return code === 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff) ? '\ufffd' : String.fromCodePoint(code);
  });
}

function css(value) {
  let rest = value.trim();
  const compounds = [];
  const combinators = [];
  while (rest) {
    if (compounds.length >= 50) {
      throw new Unsupported('CSS selector exceeds 50 compounds');
    }
    const tests = [];
    if (rest[0] === '*') {
      rest = rest.slice(1);
      tests.push(() => true);
    } else {
      const tag = identifier.exec(rest);
      if (tag) {
        const name = unescapeCss(tag[0]);
        tests.push(({node}) => {
          if (typeof node.tagName !== 'string') {
            return UNKNOWN;
          }
          if (node.namespaceURI === 'http://www.w3.org/1999/xhtml') {
            return asciiLower(node.tagName) === asciiLower(name);
          }
          if (node.tagName === name) {
            return true;
          }
          return node.namespaceURI === undefined && asciiLower(node.tagName) === asciiLower(name) ? UNKNOWN : false;
        });
        rest = rest.slice(tag[0].length);
      }
    }
    while (rest) {
      if (rest[0] === '#') {
        const id = identifier.exec(rest.slice(1));
        if (!id) {
          throw new Unsupported('Invalid CSS ID');
        }
        tests.push(attribute('id', unescapeCss(id[0]), true));
        rest = rest.slice(id[0].length + 1);
      } else if (rest[0] === '[') {
        const attr =
          /^\[\s*([\w-]+)\s*(?:=\s*("(?:[^"\\\r\n]|\\[^\r\n])*"|'(?:[^'\\\r\n]|\\[^\r\n])*'|[\w-]+)\s*)?\]/.exec(rest);
        if (!attr || !attributes.has(attr[1])) {
          throw new Unsupported('CSS attribute is not supported by the dump');
        }
        let expected = attr[2];
        if (expected !== undefined) {
          if (/^["']/.test(expected)) {
            expected = expected.slice(1, -1);
          } else if (identifier.exec(expected)?.[0] !== expected) {
            throw new Unsupported('Unquoted CSS attribute value must be an identifier');
          }
          expected = unescapeCss(expected);
        }
        tests.push(attribute(attr[1], expected));
        rest = rest.slice(attr[0].length);
      } else if (rest.startsWith(':nth-of-type(')) {
        const nth = /^:nth-of-type\(\s*([1-9]\d*)\s*\)/.exec(rest);
        if (!nth || !Number.isSafeInteger(Number(nth[1]))) {
          throw new Unsupported('Only positive integer :nth-of-type is supported');
        }
        tests.push(({node}) => {
          // Sibling arrays omit script/style nodes. Use the collector's original DOM index.
          if (typeof node.selector !== 'string') {
            return UNKNOWN;
          }
          const index = /:nth-of-type\((\d+)\)$/.exec(node.selector);
          return index
            ? Number(index[1]) === Number(nth[1])
            : node.selector === 'html'
              ? Number(nth[1]) === 1
              : UNKNOWN;
        });
        rest = rest.slice(nth[0].length);
      } else {
        break;
      }
    }
    if (!tests.length) {
      throw new Unsupported(
        'Unsupported CSS selector; use tags, IDs, saved attributes, child/descendant combinators and integer :nth-of-type',
      );
    }
    compounds.push((entry) => (entry.node.tagName === '#shadow-root' ? false : and(tests.map((test) => test(entry)))));
    if (!rest) {
      break;
    }
    const separator = /^(\s*>\s*|\s+)/.exec(rest);
    if (!separator) {
      throw new Unsupported(`Unsupported CSS syntax: ${rest.slice(0, 30)}`);
    }
    combinators.push(separator[0].includes('>') ? 'child' : 'descendant');
    rest = rest.slice(separator[0].length);
    if (!rest) {
      throw new Unsupported('CSS selector ends with a combinator');
    }
  }
  if (!compounds.length) {
    throw new Unsupported('CSS selector is empty');
  }
  const cache = new WeakMap();
  let budget = 100000;
  function evaluate(entry, index) {
    let results = cache.get(entry);
    if (!results) {
      cache.set(entry, (results = new Map()));
    }
    if (results.has(index)) {
      return results.get(index);
    }
    if (--budget < 0) {
      throw new Error('CSS selector exceeds the evaluation budget');
    }
    const result = evaluateUncached(entry, index);
    results.set(index, result);
    return result;
  }
  function evaluateUncached(entry, index) {
    const own = compounds[index](entry);
    if (own === false || index === 0) {
      return own;
    }
    const parents = [];
    for (
      let parent = entry.parent;
      parent?.node.type === 'DomNode' && sameContext(parent.node.context, entry.node.context);
      parent = parent.parent
    ) {
      if (--budget < 0) {
        throw new Error('CSS selector exceeds the evaluation budget');
      }
      parents.push(evaluate(parent, index - 1));
      if (combinators[index - 1] === 'child') {
        break;
      }
    }
    return and([own, or(parents)]);
  }
  return (entry) => evaluate(entry, compounds.length - 1);
}

function attribute(name, expected, idSelector = false) {
  return ({node}) => {
    if (
      !node.attributes ||
      typeof node.attributes !== 'object' ||
      node.errors?.length ||
      (node.sensitive && !['id', 'type'].includes(name))
    ) {
      return UNKNOWN;
    }
    if (!Object.hasOwn(node.attributes, name)) {
      return false;
    }
    const actual = node.attributes[name];
    // The collector bounds strings to 4096 characters; an exact comparison then may be lossy.
    if (typeof actual !== 'string' || actual.length >= 4096) {
      return UNKNOWN;
    }
    if (expected !== undefined && actual !== expected && asciiLower(actual) === asciiLower(expected)) {
      // CSS ID selectors ignore ASCII case in quirks mode; [id=...] and WebDriver id do not.
      if (idSelector) {
        if (node.compatMode === 'BackCompat') {
          return true;
        }
        if (node.compatMode !== 'CSS1Compat') {
          return UNKNOWN;
        }
      }
      // Some HTML type attributes compare without case. Exact dump values remain supported;
      // without the element-specific HTML rules, a case-changing comparison is uncertain.
      if (name === 'type' && (!node.namespaceURI || node.namespaceURI === 'http://www.w3.org/1999/xhtml')) {
        return UNKNOWN;
      }
    }
    return expected === undefined || actual === expected;
  };
}

export function sameContext(left, right) {
  return (
    Array.isArray(left) &&
    Array.isArray(right) &&
    left.length === right.length &&
    left.every(
      (item, index) =>
        item &&
        ['frame', 'shadow'].includes(item.type) &&
        typeof item.selector === 'string' &&
        item.type === right[index]?.type &&
        item.selector === right[index]?.selector,
    )
  );
}

export function snapshotLocator(backend, using, value) {
  if (typeof using !== 'string' || typeof value !== 'string') {
    throw new Unsupported('using and value are required for a locator');
  }
  if (backend === 'automator') {
    return automator(using, value);
  }
  if (using === 'css selector') {
    return css(value);
  }
  if (using === 'id') {
    return attribute('id', value);
  }
  throw new Unsupported(`Unsupported DOM locator: ${using}. Use css selector or id.`);
}
