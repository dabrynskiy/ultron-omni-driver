---
title: Ultron Compose operations
---

`mobile: ultronComposeAct` and `mobile: ultronComposeAssert` execute Ultron Compose operations
directly against semantics nodes. MCP exposes the same API as `ultron_compose_act` and
`ultron_compose_assert`, in both standalone and Appium MCP modes. These commands require
`composeOn` and the server's active Compose test rule. They do not change the global `driver`
setting. `composeOff` rejects them with the existing Compose-not-supported error.

Each operation returns `{operation, snapshot}`. `operation.backend` is `compose`; the snapshot
uses the existing mixed View/Compose format (whose `backend` remains `espresso`). Runtime failures
return `operation.status: "failure"` with the snapshot; MCP marks these results as errors. Invalid
arguments are rejected before execution. `ultron_last_result` / `mobile: ultronLastResult` also
retain Compose results. A snapshot failure does not replace the action's result.

MCP enables automatic snapshots before executing. Direct HTTP/Appium clients should enable
`ultronSnapshotEnabled: true` to collect them; otherwise the snapshot status is `disabled`.

## Common parameters

| Parameter | Description |
| --- | --- |
| `matcher` | Required Compose matcher; see the DSL below. Each target must be unique. |
| `useUnmergedTree` | Default `true`, matching existing driver locators. Applies to both the operation and its snapshot; does not change subsequent commands. |
| `timeoutMs` | Optional Ultron wait/retry budget, 0..60000 ms. Zero makes a single attempt. |
| `name` | Optional nonempty label used in operation diagnostics. |
| `options` | Operation-specific object. Unknown and irrelevant options are rejected. |

The matcher is re-evaluated on Android for each attempt. No stale snapshot node ID or cached first
match is used. Compose synchronization settles recomposition before the post-operation snapshot.
Matching and acting are still subject to UI changes; a successful invocation should be followed
by a check of the expected result. After transport loss, observe the UI before repeating a mutation.

## Compose matcher DSL

Matchers are `{name, args?}`. They do not accept Espresso `class` or window `scope` fields.

| Name | Arguments |
| --- | --- |
| `hasTestTag` | `[tag]` |
| `hasText`, `hasContentDescription` | `[text, substring?, ignoreCase?]`; booleans default false |
| `allOf`, `anyOf` | One to 32 nested matchers |
| `not` | One nested matcher |
| `hasParent`, `hasAnyAncestor`, `hasAnyDescendant`, `hasAnySibling` | One nested matcher |
| `propertyEquals` | `[propertyName, expectedJSON]`; compare the property's normalized snapshot representation |
| `hasAction` | `[semanticsActionName]`, e.g. `"SetProgress"` |
| `isRoot`, `isEnabled`, `isNotEnabled`, `isFocused`, `isNotFocused`, `isSelected`, `isNotSelected`, `isSelectable`, `isToggleable`, `isOn`, `isOff`, `hasClickAction`, `hasScrollAction`, `hasSetTextAction` | No arguments |

The DSL is limited to nesting depth 16 and 128 matcher nodes. It uses explicit factories and does
not evaluate Kotlin or reflect caller-supplied classes. Use ancestor/parent constraints and tags
to distinguish duplicate targets. `propertyEquals` compares existing semantics; a missing property
does not equal null. Text and descriptions can be arrays, enum values use their string form,
`TextSelectionRange` is `{start,end}`, and `ProgressBarRangeInfo` is
`{current,min,max,steps,indeterminate}`. Action lambdas are not readable property values.

```json
{
  "name": "allOf",
  "args": [
    {"name": "hasText", "args": ["Save"]},
    {"name": "hasAnyAncestor", "args": [{"name": "hasTestTag", "args": ["editor"]}]}
  ]
}
```

## Actions

`action` is required. `text` is required only for text input/replacement actions; empty text is valid.

| Action | Arguments/options |
| --- | --- |
| `click`, `longClick`, `doubleClick` | `options.position`: `center` (default), `topLeft`, `topCenter`, `topRight`, `centerLeft`, `centerRight`, `bottomLeft`, `bottomCenter`, `bottomRight`; integer `offsetX/offsetY` in local pixels. Long click also accepts `durationMs`; double click accepts `delayMs`. Omission uses Compose's duration/delay defaults. |
| `inputText`, `typeText`, `replaceText`, `setText` | Top-level `text`. Input inserts text using the text input API; replacement replaces the field. `setText` calls the SetText semantics action. |
| `clearText`, `imeAction` | No options. IME invokes the field's declared action, such as Search or Done. |
| `scrollTo` | Select the target node, which must already exist. |
| `scrollToIndex` | Select the container; `options.index` is a nonnegative zero-based index. |
| `scrollToKey` | Select the container; `options.key`, optional `keyType`: `string` (default), `int`, `long`. Int keys are JSON integers; Long keys are decimal strings in signed 64-bit range. Key type must match the app's Lazy key type. |
| `scrollToNode` | Select the container; required top-level `itemMatcher`. Searches while scrolling, including off-screen Lazy items. |
| `swipeUp`, `swipeDown`, `swipeLeft`, `swipeRight`, `swipe` | Optional `startXOffset`, `endXOffset`, `startYOffset`, `endYOffset`, `durationMs`, following Ultron ComposeSwipeOption. `swipe` uses Ultron's horizontal swipe calculation. |
| `expand`, `collapse`, `dismiss` | Call the corresponding component semantics action. |
| `setProgress` | Required `options.value`, finite float in the component's range, which is not necessarily 0..1. |
| `customAccessibilityAction` | Required exact `options.label`; available labels appear in snapshot `config.CustomActions`. |
| `selectText` | Required nonnegative `options.start/end`, using input text indices; reversed ranges are allowed. |
| `setSelection` | Required `start/end/traversalMode`. Traversal mode uses original text indices; otherwise indices refer to transformed accessibility text. |
| `copyText`, `cutText`, `pasteText` | Use the component's semantics and system clipboard. Copy/cut require an active selection. |
| `pressKey` | Required Android `options.keyCode`; optional nonnegative `metaState`, `keyAction: "down"` (default) or `"up"`. Sends one event; send release separately where needed. |

Durations/delays must be 1..60000 ms; offsets and indices must fit signed Int. Actions require the
corresponding semantics support; an observed action name does not guarantee a particular UI effect.

```js
await driver.execute('mobile: ultronComposeAct', {
  matcher: {name: 'hasTestTag', args: ['feed']},
  action: 'scrollToNode',
  itemMatcher: {name: 'hasText', args: ['Order #123']},
  timeoutMs: 10000,
});
```

## Assertions

`assertion` defaults to `exists`. Checks execute on Android with Ultron waiting, rather than being
inferred from a potentially incomplete snapshot.

| Assertion | Additional arguments |
| --- | --- |
| `exists`, `doesNotExist`, `isDisplayed`, `isNotDisplayed` | None. Existence and visibility are separate checks. |
| `isEnabled`, `isNotEnabled`, `isFocused`, `isNotFocused` | None |
| `isSelected`, `isNotSelected`, `isSelectable` | None |
| `isOn`, `isOff`, `isIndeterminate`, `isToggleable` | None. Toggle state is independent of Selected. |
| `hasClickAction`, `hasNoClickAction` | Check action presence, independently of enabled state. |
| `textEquals` | Required `expected: string[]`; optional `options.includeEditableText` (default true). |
| `contentDescriptionEquals` | Required `expected: string[]`. Arrays preserve multiple values in merged semantics; `[]` asserts no values. |
| `textContains`, `contentDescriptionContains` | Required top-level `text`; optional `options.substring/ignoreCase` (both default false, following Ultron). Set `substring: true` for substring matching. |
| `valueEquals` | Required top-level `text`; uses Compose's semantic value assertion. |
| `rangeInfoEquals` | Required `options.current/min/max`; optional nonnegative `steps` (default 0); `min <= current <= max`. |
| `widthIsAtLeast`, `widthIsEqualTo`, `heightIsAtLeast`, `heightIsEqualTo` | Required finite, nonnegative `options.dp`. These use dp, unlike snapshot bounds in pixels. |
| `matches` | Required top-level `condition`, using the same Compose matcher DSL. |

```json
{
  "matcher": {"name": "hasTestTag", "args": ["notifications"]},
  "assertion": "isOn",
  "timeoutMs": 5000
}
```

Lazy containers compose only part of their data. `doesNotExist` checks the current semantics tree;
it does not prove that an item is absent from the entire data set. Child count is not item count.
Use container scrolling to find distant items. This API does not add Lazy item-count assertions,
arbitrary Kotlin blocks, arbitrary semantics lambdas, or mouse input.
