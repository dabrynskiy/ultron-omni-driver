import type {EspressoDriver} from '../driver.js';

/** Espresso JSON matchers use {name, class?, args?, scope?}. */
export interface UltronMatcher {
  name: string;
  class?: string;
  args?: unknown;
  scope?: UltronMatcher;
}

export interface UltronSnapshotResult {
  snapshotId: string;
  backend: 'espresso' | 'automator';
  mode?: 'ultron' | 'automator';
  context?: Record<string, unknown> | null;
  schemaVersion: number;
  status: 'captured' | 'partial' | 'unavailable' | 'disabled';
  roots: Record<string, unknown>[];
  nodeCount: number;
  truncated: boolean;
  errors: string[];
  capturedAtMs: number;
  durationMs: number;
}

export interface UltronOperationResult {
  operation: {
    status: 'success' | 'failure';
    name: string;
    type: string;
    durationMs: number;
    error: string | null;
    backend?: 'espresso' | 'compose' | 'automator' | 'web';
  };
  snapshot: UltronSnapshotResult;
  value?: unknown;
  element?: Record<string, string> | null;
}

export type UltronBatchCommand =
  | 'ultronAct'
  | 'ultronAssert'
  | 'ultronComposeAct'
  | 'ultronComposeAssert'
  | 'ultronRecycler'
  | 'ultronBack'
  | 'ultronHideKeyboard'
  | 'ultronElement'
  | 'ultronAutomatorAct'
  | 'ultronAutomatorAssert'
  | 'ultronWebAct'
  | 'ultronWebAssert';

export interface UltronBatchStep {
  id: string;
  command: UltronBatchCommand;
  /** The same parameters as the corresponding mobile command, without a session ID. */
  params: Record<string, unknown>;
}

export interface UltronBatchResult {
  schemaVersion: 1 | 2;
  status: 'success' | 'failure';
  failedStepId: string | null;
  durationMs: number;
  steps: {
    id: string;
    command: UltronBatchCommand;
    status: 'success' | 'failure' | 'skipped';
    operation: UltronOperationResult['operation'] | null;
    /** Only the failed step has a snapshot, regardless of automatic snapshot settings. */
    snapshot: UltronSnapshotResult | null;
    value?: unknown;
    element?: Record<string, string> | null;
  }[];
}

export async function mobileUltronBack(this: EspressoDriver): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_back',
    'POST',
    {},
  )) as UltronOperationResult;
}

export async function mobileUltronHideKeyboard(this: EspressoDriver): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_hide_keyboard',
    'POST',
    {},
  )) as UltronOperationResult;
}

export async function mobileUltronElement(
  this: EspressoDriver,
  backend: string,
  using: string,
  value: string,
  action: string,
  text?: string,
  requireUnique?: boolean,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_element', 'POST', {
    backend,
    using,
    value,
    action,
    ...(text === undefined ? {} : {text}),
    ...(requireUnique === undefined ? {} : {requireUnique}),
  })) as UltronOperationResult;
}

export async function mobileUltronBatch(
  this: EspressoDriver,
  steps: UltronBatchStep[],
  schemaVersion: number = 1,
): Promise<UltronBatchResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_batch', 'POST', {
    schemaVersion,
    steps,
  })) as UltronBatchResult;
}

export async function mobileUltronSnapshot(
  this: EspressoDriver,
  maxDepth?: number,
  maxNodes?: number,
  useUnmergedTree?: boolean,
  mode?: 'ultron' | 'automator',
): Promise<UltronSnapshotResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_snapshot', 'POST', {
    ...Object.fromEntries(
      Object.entries({maxDepth, maxNodes, useUnmergedTree, mode}).filter(([, value]) => value !== undefined),
    ),
  })) as UltronSnapshotResult;
}

export async function mobileUltronAct(
  this: EspressoDriver,
  matcher: UltronMatcher,
  action: string,
  text?: string,
  timeoutMs?: number,
  offsetX?: number,
  offsetY?: number,
  keyCode?: number,
  metaState?: number,
  flags?: number,
  autoScroll?: boolean,
  name?: string,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_act', 'POST', {
    matcher,
    action,
    text,
    timeoutMs,
    ...Object.fromEntries(
      Object.entries({offsetX, offsetY, keyCode, metaState, flags, autoScroll, name}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  })) as UltronOperationResult;
}

export async function mobileUltronAssert(
  this: EspressoDriver,
  matcher: UltronMatcher,
  condition?: UltronMatcher,
  exists?: boolean,
  timeoutMs?: number,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_assert', 'POST', {
    matcher,
    condition,
    exists,
    timeoutMs,
  })) as UltronOperationResult;
}

export async function mobileUltronLastResult(this: EspressoDriver): Promise<UltronOperationResult | null> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_last_result',
    'GET',
  )) as UltronOperationResult | null;
}

/** Positions refer to the outer adapter (including ConcatAdapter), starting at zero. */
export async function mobileUltronRecycler(
  this: EspressoDriver,
  matcher: UltronMatcher,
  action: string,
  position?: number,
  itemMatcher?: UltronMatcher,
  childMatcher?: UltronMatcher,
  condition?: UltronMatcher,
  itemCount?: number,
  searchLimit?: number,
  text?: string,
  timeoutMs?: number,
  name?: string,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_recycler', 'POST', {
    matcher,
    action,
    ...Object.fromEntries(
      Object.entries({
        position,
        itemMatcher,
        childMatcher,
        condition,
        itemCount,
        searchLimit,
        text,
        timeoutMs,
        name,
      }).filter(([, value]) => value !== undefined),
    ),
  })) as UltronOperationResult;
}

export async function mobileUltronComposeAct(
  this: EspressoDriver,
  matcher: UltronComposeMatcher,
  action: string,
  text?: string,
  itemMatcher?: UltronComposeMatcher,
  options?: Record<string, unknown>,
  useUnmergedTree?: boolean,
  timeoutMs?: number,
  name?: string,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_compose_act',
    'POST',
    Object.fromEntries(
      Object.entries({matcher, action, text, itemMatcher, options, useUnmergedTree, timeoutMs, name}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}

export async function mobileUltronComposeAssert(
  this: EspressoDriver,
  matcher: UltronComposeMatcher,
  assertion?: string,
  text?: string,
  expected?: string[],
  condition?: UltronComposeMatcher,
  options?: Record<string, unknown>,
  useUnmergedTree?: boolean,
  timeoutMs?: number,
  name?: string,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_compose_assert',
    'POST',
    Object.fromEntries(
      Object.entries({matcher, assertion, text, expected, condition, options, useUnmergedTree, timeoutMs, name}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}

/** Bounded Compose semantics DSL, independent of Espresso matchers. */
export interface UltronComposeMatcher {
  name: string;
  args?: unknown[];
}

export async function mobileUltronAutomatorAct(
  this: EspressoDriver,
  strategy: string,
  locator: string,
  action: string,
  text?: string,
  packageName?: string,
  index?: number,
  timeoutMs?: number,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_automator_act',
    'POST',
    Object.fromEntries(
      Object.entries({strategy, locator, action, text, packageName, index, timeoutMs}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}

export async function mobileUltronAutomatorAssert(
  this: EspressoDriver,
  strategy: string,
  locator: string,
  assertion?: string,
  text?: string,
  expected?: boolean,
  packageName?: string,
  index?: number,
  timeoutMs?: number,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_automator_assert',
    'POST',
    Object.fromEntries(
      Object.entries({strategy, locator, assertion, text, expected, packageName, index, timeoutMs}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}

export async function mobileUltronWebAct(
  this: EspressoDriver,
  webViewMatcher: UltronMatcher,
  locator: Record<string, string>,
  action: string,
  text?: string,
  frames?: ({index: number} | {idOrName: string})[],
  timeoutMs?: number,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_web_act',
    'POST',
    Object.fromEntries(
      Object.entries({webViewMatcher, locator, action, text, frames, timeoutMs}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}

export async function mobileUltronWebAssert(
  this: EspressoDriver,
  webViewMatcher: UltronMatcher,
  locator: Record<string, string>,
  assertion?: string,
  text?: string,
  frames?: ({index: number} | {idOrName: string})[],
  timeoutMs?: number,
): Promise<UltronOperationResult> {
  return (await this.espresso.jwproxy.command(
    '/appium/execute_mobile/ultron_web_assert',
    'POST',
    Object.fromEntries(
      Object.entries({webViewMatcher, locator, assertion, text, frames, timeoutMs}).filter(
        ([, value]) => value !== undefined,
      ),
    ),
  )) as UltronOperationResult;
}
