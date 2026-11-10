---
title: View and Compose snapshots for LLM agents
---

The Ultron extensions describe Android Views and, in the `composeOn` flavor, embedded Compose
semantics trees. They execute Espresso actions/assertions with a post-operation snapshot. Explicit
[Compose operations](compose-operations.md) provide the same result format for semantics nodes.
The `composeOff` flavor includes Views only. Accessibility trees of other apps and Web DOM trees
are outside this API. `ultronAct`/`ultronAssert` explicitly use Espresso, regardless of the current `driver`
setting; adding Compose nodes to the observation does not make `ultronAct`/`ultronAssert` Compose executors.

## Sensitive fields

Snapshots replace password values with `[REDACTED]` and mark the node with `sensitive: true`.
This applies to text, numeric and web password fields, visible-password input types, and password
transformations. Text, transformed text, descriptions, tags, hints and errors on these Views are
redacted. Compose nodes marked `Password` or `IsSensitiveData`, and their descendants, have their
semantics values redacted; test tags and structural/action metadata remain available for locating
and operating controls. Ordinary text fields keep their values. Redaction cannot recognize secrets
in ordinary, unmarked app content; use test accounts and synthetic data for such screens.
Operation names and errors are also redacted for text input and snapshots containing sensitive
nodes, including the saved `ultronLastResult`, because Ultron diagnostics may echo input values.

## Observe and act

```javascript
const snapshot = await driver.execute('mobile: ultronSnapshot', {maxDepth: 100, maxNodes: 5000});
await driver.updateSettings({ultronSnapshotEnabled: true});
const target = {
  class: 'LlmViewMatchers',
  name: 'propertyEquals',
  args: ['resourceId', 'com.example:id/submit'],
};
const result = await driver.execute('mobile: ultronAct', {matcher: target, action: 'click'});
// Inspect result.operation.status and result.snapshot.status separately.
```

Actions: `click`, `doubleClick`, `longClick`, `typeText`, `replaceText`, `clearText`, `scrollTo`,
`swipeUp`, `swipeDown`, `swipeLeft`, `swipeRight`, `pressKey`, `closeSoftKeyboard`, and the eight
positional clicks below. Text actions require `text` (an empty string is valid).
An optional `timeoutMs` overrides the Ultron timeout for this operation, from 0 to 60000 ms.
Selectors use Espresso's normal uniqueness rules; an ambiguous selector fails instead of clicking
the first match. Only the supported Ultron retry policy runs; the extension adds no outer retries.
After a lost HTTP response, observe the UI before deciding whether to repeat a mutating action.

| Action | Optional offsets (integer pixels, default 0) |
| --- | --- |
| `clickTopLeft`, `clickTopRight`, `clickBottomLeft`, `clickBottomRight` | `offsetX`, `offsetY` |
| `clickTopCenter`, `clickBottomCenter` | `offsetY` |
| `clickCenterLeft`, `clickCenterRight` | `offsetX` |

Offsets follow Ultron: positive X moves right and positive Y moves down from the named location.
Use inward offsets to avoid tapping outside the View. Offsets on other actions, or on an unsupported
axis, are rejected. For example:

```javascript
await driver.execute('mobile: ultronAct', {
  matcher: target, action: 'clickTopRight', offsetX: -12, offsetY: 12, name: 'Submit',
});
await driver.execute('mobile: ultronAct', {
  matcher: target, action: 'pressKey', keyCode: 29, metaState: 1, flags: 0,
});
```

`pressKey` requires `keyCode` (an Android KEYCODE integer greater than 0 and below the device's
`KeyEvent.getMaxKeyCode()`, as required by Espresso). Optional `metaState` and `flags` are non-negative
32-bit integer bitmasks. The matched View must be displayed; key events go to the current focus,
without automatically focusing the matched View. Focus the intended input before sending keys.
Omitting the options uses Ultron's native integer key action. Extended options retain Espresso's
injection handling and Back synchronization, and are reported as a `PRESS_KEY` operation.

`autoScroll` optionally calls Ultron's `withAutoScroll` for this action only; omission preserves the
configured default. It controls actions for which Ultron implements that switch, including ordinary
clicks, double/long clicks, text typing and swipes. Ultron's positional clicks always use its custom
scrolling click action. `name` is an optional non-empty element label used in operation names and
diagnostics. Neither option changes global Ultron configuration. `closeSoftKeyboard` uses the matched
View's Ultron action; standalone MCP also offers the global `ultron_hide_keyboard` tool.

```javascript
await driver.execute('mobile: ultronAssert', {
  matcher: target,
  condition: {class: 'LlmViewMatchers', name: 'propertyEquals', args: ['enabled', true]},
  timeoutMs: 2000,
});
await driver.execute('mobile: ultronAssert', {matcher: target, exists: false, timeoutMs: 2000});
```

Without a condition, `ultronAssert` checks existence. `exists: false` checks absence and cannot be
combined with `condition`. Window selection belongs in `matcher.scope`, not `condition.scope`.
Invalid arguments/selectors raise Appium errors. Execution/assertion failures return an operation
with `status: "failure"` and its snapshot; callers must inspect this status to determine test success.

The MCP `ultron_assert` tool also offers [named assertions](../../packages/ultron-mcp/README.md#named-assertions)
such as `assertion: "isDisplayed"`, `assertion: "hasText", text: "Saved"`, and
`assertion: "isDisplayingAtLeast", percentage: 90`. MCP converts these to the existing driver
`condition`/`exists` arguments; the direct `mobile: ultronAssert` contract is unchanged.

## Snapshot contract

The response contains `schemaVersion`, `backend: "espresso"`, `snapshotId`, `status`, `roots`,
`nodeCount`, `truncated`, `errors`, `capturedAtMs` and `durationMs`. `backend: "espresso"` identifies
the root provider, not the types of children in the tree. Status is `captured`, `partial`
or `unavailable`. With automatic capture disabled, operation responses contain `status: "disabled"`
and an empty tree; explicit `ultronSnapshot` still collects a snapshot. Collection uses the active
Espresso roots whose decor View has `visibility=VISIBLE`;
all their Android View children, including GONE/INVISIBLE subtrees, are retained. The UI thread reads
properties and traverses the tree; HTTP/Gson serialization runs on the server thread.

Each root node includes a `window` object. Nodes include `children` when applicable. `child_index`
is the original ViewGroup index and `child_count` includes all children, even when depth/node limits
truncate the dump. `maxDepth` defaults to 100 (range 0..100); `maxNodes` defaults to 5000 (1..10000),
shared across roots, Views and semantics nodes. A truncated node contains `truncated` and `truncation_reason`. Text is preserved
in full. There is no byte-size guarantee for screens with unusually large text or tags.

UI-thread scheduling/collection has a 2-second wait limit. A blocked UI produces an unavailable
snapshot without changing the operation result. A running getter cannot be forcibly interrupted.
Field/node errors are recorded separately so other nodes remain available. Dumps are observations
after operations; they do not freeze the application's asynchronous background work.

## Matching dump properties

`LlmViewMatchers.propertyEquals(name, value)` compares any scalar field, including null. Numbers are
compared numerically, so `1` and `1.0` are equal. `LlmViewMatchers.withProperty(name, matcher)` accepts
an existing Hamcrest matcher such as `containsString`, `greaterThan`, `closeTo`, `not`, `equalTo`.
Both factories also work through ordinary `findElement('-android viewmatcher', JSON.stringify(...))`.

```json
{
  "name": "allOf",
  "args": [
    {"class": "LlmViewMatchers", "name": "propertyEquals", "args": ["text", "Continue"]},
    {"class": "LlmViewMatchers", "name": "withProperty", "args": ["width", {"name": "greaterThan", "args": [100]}]}
  ],
  "scope": {"class": "LlmRootMatchers", "name": "propertyEquals", "args": ["category", "dialog"]}
}
```

The dump and these factories share getters. Supported properties:

| Properties | Meaning |
| --- | --- |
| `type`, `className` | `View` and exact runtime class name |
| `viewId`, `resourceId` | Numeric View ID and full resource name; `NO_ID` or hex when unresolved |
| `contentDescription` | View content description as plain text, or null |
| `visibility` | Own visibility: `VISIBLE`, `INVISIBLE`, `GONE` |
| `effective_visibility` | GONE if any ancestor is GONE, otherwise INVISIBLE if any is INVISIBLE, otherwise VISIBLE |
| `displayed` | Effective visibility is VISIBLE and global visible rectangle is nonempty; not an occlusion/hit-test guarantee |
| `alpha`, `enabled`, `focused`, `focusable`, `has_focusable`, `clickable`, `is_selected` | Own getters; `has_focusable` includes descendants |
| `layoutParams`, `layout_params_class` | Simple/full layout params class name, or null |
| `tag`, `tag_type`, `tag_is_stringified` | Scalar value, runtime type, and whether an object was represented by its string value |
| `bounds.l/t/r/b`, `width`, `height` | Location on screen plus layout dimensions; bounds do not model rotation/scaling |
| `visible_bounds.l/t/r/b` | Clipped visible rectangle, or `visible_bounds: null` when not displayed |
| `child_index`, `child_count` | Original sibling index (-1 without ViewGroup parent), total child count |
| `scrollable`, `scroll_x`, `scroll_y` | Can scroll in any direction *now*, and current offsets; present even when false/zero |
| `text`, `transformed_text`, `hint`, `error_text`, `input_type`, `ime_target`, `has_links` | Available on every TextView, not only EditText; transformed_text is the Espresso-compatible transformed text or null |
| `is_checked` | Available on any View implementing Checkable |

Missing type-specific properties never match, even against null. Tags preserve JSON scalar types;
other objects use `toString()` for both dump and matching, with `tag_is_stringified: true`. Include
`tag_type` when different runtime tag types need distinguishing. Nonfinite float/double tags are
represented as strings. Snapshot bookkeeping (`children`, `truncated`, `errors`) is not a View property;
use Espresso structural matchers (`withParent`, `hasDescendant`, etc.) for hierarchy relations.

`LlmRootMatchers` provides the same factories for `scope`. Properties: `type`, `category`, `is_dialog`,
`is_popup`, `is_toast`, `is_overlay`, `root_class`. Window type comes from Espresso Root's layout params,
without private Android reflection. Dialog detection uses Espresso's token/type checks and attached
dialog type. Popup/toast/dialog take precedence over overlay; category is `unknown` when params are
unavailable, with a partial snapshot and diagnostic. These categories describe instrumentation roots,
not every system window on the device.

## RecyclerView lists

`mobile: ultronRecycler` operates on a unique AndroidX RecyclerView, including subclasses. It uses
the same Ultron timeout/retry and post-operation snapshot pipeline as `ultronAct`. The list is
selected by `matcher`; `matcher.scope` selects its window. Item, child and condition matchers must
not contain a window scope. It always uses Espresso, regardless of the standard command backend.

```javascript
const list = {class: 'LlmViewMatchers', name: 'propertyEquals',
  args: ['resourceId', 'com.example:id/feed']};
await driver.execute('mobile: ultronRecycler', {
  matcher: list,
  action: 'click',
  itemMatcher: {class: 'ViewMatchers', name: 'hasDescendant', args: [
    {class: 'ViewMatchers', name: 'withText', args: ['Article title']},
  ]},
  childMatcher: {class: 'ViewMatchers', name: 'withContentDescription', args: ['Open']},
  searchLimit: 500,
  timeoutMs: 5000,
});
await driver.execute('mobile: ultronRecycler', {
  matcher: list, action: 'scrollToItem', position: 60,
});
await driver.execute('mobile: ultronRecycler', {
  matcher: list, action: 'assertItemCount', itemCount: 80,
});
```

| Argument | Meaning |
| --- | --- |
| `matcher`, `action` | Required list selector and operation |
| `position` / `itemMatcher` | Exactly one item selector, except for `assertItemCount`. Position is zero-based in the outer adapter, including ConcatAdapter headers. `itemMatcher` matches the item root; use `hasDescendant` for a row's text. |
| `childMatcher` | Optional unique descendant within the item, for clicks, text actions and `assertItem` |
| `condition` | Required only for `assertItem`; matches the item or selected child |
| `itemCount` | Required only for `assertItemCount`; non-negative adapter count |
| `searchLimit` | With `itemMatcher` only; 1..10000, default 1000 adapter positions per attempt |
| `text` | Required only for `typeText` / `replaceText`; empty string is accepted |
| `timeoutMs`, `name` | Optional Ultron timeout (0..60000 ms) and diagnostic label |

Actions: `scrollToItem`, `click`, `longClick`, `doubleClick`, `typeText`, `replaceText`, `clearText`,
`assertItem`, `assertItemExists`, `assertItemNotExists`, `assertItemCount`. Interactions and
`assertItem` scroll first; existence and count checks do not scroll. An item or child used for an
interaction must be unique. Existence checks accept any matching item. Empty/missing adapters
have count zero. Assertions check the current adapter data with Ultron waiting; absence is not
a promise that an item will never appear later.

Off-screen search binds temporary ViewHolders on the UI thread, following the adapter-search
approach used by Ultron. Use content matchers: visibility, parent/window membership and attached
state cannot describe an off-screen temporary holder reliably. Binding invokes application code;
the search does not request future Paging pages. If the limit leaves an unexamined suffix, the
operation fails rather than claiming absence or a unique item. Existence can succeed as soon as
one match is found. Increase the limit or select an explicit position when appropriate. Search
cost is bounded by adapter positions per attempt; Espresso idle waiting and adapter binding can
add time beyond the retry timeout.

Every View node now contains `is_recycler_view`, determined from its runtime type. Lists also
contain `recycler_item_count`, `recycler_layout_manager` (full class name), and
`recycler_has_stable_ids`; these are null when the corresponding adapter/manager is absent.
Attached item roots contain `recycler_position` (absolute adapter position),
`recycler_binding_position` (position within the binding adapter), `recycler_item_view_type` and
`recycler_item_id`. Unknown positions and unavailable stable IDs are null. Stable IDs are decimal
strings so all 64 bits survive JSON/JavaScript. These observations can change after recycling or
adapter updates; `child_index` is still the index among attached Views, not an adapter position.
The dump includes attached Views only, not a serialization of the entire adapter.

All these fields work with `LlmViewMatchers` and MCP's offline `ultron_check_matcher`. The offline
checker also accepts `ViewMatchers.isAssignableFrom("androidx.recyclerview.widget.RecyclerView")`
using the explicit runtime flag. Older snapshots without the flag give an indeterminate result.
Other arbitrary class-inheritance queries remain unsupported offline. RecyclerView is supplied
by the application; the server does not package a replacement version. Applications without it
still support ordinary snapshots with `is_recycler_view: false`.

## Embedded Compose semantics

`composeOn` detects each `ViewRootForTest` in the Android View hierarchy, following the approach in
`LlmReadables`. The host remains a `type: "View"` node. Its `children` start with a semantics root,
followed by its native View children, preserving Android Views embedded with `AndroidView` as well.
`child_index` and `child_count` on Views still describe the native ViewGroup, so they do not include
the additional semantics root. A UI component may have both View and semantics representations.

Semantics nodes contain `type: "SemanticsNode"`, `node_id`, screen-coordinate `bounds`,
`child_count`, optional `test_tag`, `config` and recursive `children`. Their `visibility` and
`effective_visibility` describe the host View and its ancestors, not individual semantics visibility.
GONE/INVISIBLE hosts retain their semantics subtrees so duplicate or hidden targets remain observable.
The semantics root adds `semantics_root: true`, `semantics_tree: "unmerged" | "merged"`,
`compose_view_class`, `compose_view_resource_id` and `compose_view_layout_params`.

The default is the unmerged tree, matching the driver's existing Compose locators. For a merged
observation, call `mobile: ultronSnapshot` with `useUnmergedTree: false`. This only affects that
snapshot; automatic post-operation snapshots for View commands remain unmerged. Explicit Compose
operations use their per-call `useUnmergedTree` for both execution and the post-operation snapshot. Collection does not switch `driver`
or require an active Compose test rule. Node IDs are observations, not W3C element handles.

`config` properties are sorted by name. Annotated text becomes plain text, lists retain JSON scalar
types, action names are collected in `actions`, and Unit properties in `units`. Merge/clear semantics
are marked by `merge_descendants` / `clear_and_set_semantics`. Custom accessibility actions expose
their labels. Progress ranges serialize as `{current,min,max,steps,indeterminate}` and text selection
ranges as `{start,end}`; other non-scalar values use string representations. Action lambdas and Compose objects
are never passed to Gson for reflective serialization. Individual property failures produce partial
snapshots while other nodes remain available. `LlmViewMatchers` still matches Android Views only;
use `ultron_compose_act` / `ultron_compose_assert` or the existing Compose locator/action path
to interact with semantics nodes. Explicit Compose operations use the requested semantics tree
for both matching and their automatic post-operation snapshot.

### Verify the mixed dump

The Compose instrumentation fixture uses Java 11 inline APIs. From `espresso-server`, run both
flavors against an explicitly selected emulator:

```sh
ANDROID_SERIAL=emulator-5556 ./gradlew \
  :library:connectedComposeOnDebugAndroidTest :library:connectedComposeOffDebugAndroidTest \
  -PappiumJvmTarget=11 -PappiumSourceCompatibility=VERSION_11 -PappiumTargetCompatibility=VERSION_11 \
  -Pandroid.testInstrumentationRunnerArguments.package=io.appium.espressoserver.test.ultron
```

The [MCP server](../../packages/ultron-mcp/README.md) exposes these extensions directly over the
Android HTTP connection, without Appium. An optional plugin also supports Appium MCP remote sessions.

Standard factory arguments are converted according to the factory parameter: Class arguments accept
`android.widget.Button` or `android.widget.Button.class`; ordinary text (even `String`/`String.class`)
stays text. Enum parameters accept exact enum constant names (`withEffectiveVisibility("VISIBLE")`).
Finite JSON fractional values can be converted to float parameters (`withAlpha(0.5)`). Generic
Hamcrest numeric matchers remain type-sensitive; use `propertyEquals` for scalar equality.

## Ultron listener and compatibility

Automatic capture is disabled by default. The Appium setting `ultronSnapshotEnabled: true` registers
`UltronSnapshotListener`; `false` removes it and clears the saved result. Server startup/shutdown and
session creation/deletion reset the setting to `false`. Values must be booleans. It captures
after the outermost Espresso Ultron operation completes, including failed operations and after the
final retry, once per lifecycle. Nested operations do not collect duplicate trees. Snapshot collection
uses no Ultron operation and cannot recursively trigger itself. Errors during collection do not
replace the original operation result. `ultronAct`/`ultronAssert` return that listener result in the
same response when the setting is enabled. If application configuration disables all Ultron listeners
while this setting remains enabled, these extensions collect a fallback snapshot at their own boundary.
With `ultronSnapshotEnabled: false`, they return `snapshot.status: "disabled"` without collection or
registration. Manual `ultronSnapshot` calls work independently of the setting and do not enable it.

Ordinary W3C commands retain their original return values and errors. After a standard Espresso
Ultron operation, `mobile: ultronLastResult` returns its saved `{operation, snapshot}`, or null if
none has been captured in this session. This is only the latest observation, not a command journal
or a reconnect/idempotency guarantee. Starting/deleting a session clears it. HTTP device commands
are serialized to keep operations and observations from interleaving.
