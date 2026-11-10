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
  backend: 'espresso';
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
  operation: {status: 'success' | 'failure'; name: string; type: string; durationMs: number; error: string | null; backend?: 'espresso' | 'compose'};
  snapshot: UltronSnapshotResult;
}

export async function mobileUltronSnapshot(
  this: EspressoDriver,
  maxDepth?: number,
  maxNodes?: number,
  useUnmergedTree?: boolean,
): Promise<UltronSnapshotResult> {
  return (await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_snapshot', 'POST', {
    maxDepth,
    maxNodes,
    useUnmergedTree,
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
      Object.entries({offsetX, offsetY, keyCode, metaState, flags, autoScroll, name})
        .filter(([, value]) => value !== undefined),
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
    ...Object.fromEntries(Object.entries({position, itemMatcher, childMatcher, condition, itemCount, searchLimit, text, timeoutMs, name})
      .filter(([, value]) => value !== undefined)),
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
  return await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_compose_act', 'POST',
    Object.fromEntries(Object.entries({matcher, action, text, itemMatcher, options, useUnmergedTree, timeoutMs, name})
      .filter(([, value]) => value !== undefined))) as UltronOperationResult;
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
  return await this.espresso.jwproxy.command('/appium/execute_mobile/ultron_compose_assert', 'POST',
    Object.fromEntries(Object.entries({matcher, assertion, text, expected, condition, options, useUnmergedTree, timeoutMs, name})
      .filter(([, value]) => value !== undefined))) as UltronOperationResult;
}

/** Bounded Compose semantics DSL, independent of Espresso matchers. */
export interface UltronComposeMatcher {
  name: string;
  args?: unknown[];
}
