---
title: Batch operations
---

# Batch operations

`mobile: ultronBatch` executes Espresso, Compose, RecyclerView, navigation and locator-based element operations
sequentially inside Android instrumentation, in one request. It stops at the first failed
operation and captures one snapshot for that step only. Successful and
skipped steps have `snapshot: null`.

`schemaVersion: 2` additionally supports Ultron UiAutomator and Web Atom actions/assertions,
and chooses failure diagnostics from the failed step's executor. Version `1` remains the default
and keeps the session-selected snapshot mode.

The batch uses the same matchers, arguments, Ultron execution and per-operation retries as
individual commands. It does not change the global `driver` or `ultronSnapshotEnabled` settings.
Failure capture works even when automatic snapshots are disabled. When automatic snapshots are
enabled, the batch suppresses them for its steps and resumes normal behavior afterwards.

## Request

```javascript
const result = await driver.execute('mobile: ultronBatch', {
  schemaVersion: 1,
  steps: [
    {
      id: 'open',
      command: 'ultronAct',
      params: {
        matcher: {class: 'ViewMatchers', name: 'withText', args: ['Details']},
        action: 'click',
        timeoutMs: 5000,
      },
    },
    {
      id: 'title',
      command: 'ultronComposeAssert',
      params: {
        matcher: {name: 'hasTestTag', args: ['details_title']},
        assertion: 'textEquals',
        expected: ['Details'],
        timeoutMs: 5000,
      },
    },
  ],
});
```

The request accepts only `schemaVersion` (`1` or `2`, optional, defaults to `1`) and `steps` (required,
1–100 entries). Each step requires exactly `id`, `command` and `params`. IDs must be unique,
nonblank strings of at most 128 characters. Commands omit the `mobile: ` prefix:

| Command | Parameters |
| --- | --- |
| `ultronAct` | [Espresso actions](llm-views.md), including `matcher`, `action` and optional action arguments |
| `ultronAssert` | [Espresso assertions](llm-views.md), using `matcher`, `condition` or `exists`, and optional `timeoutMs` |
| `ultronComposeAct` | [Compose actions](compose-operations.md) |
| `ultronComposeAssert` | [Compose assertions](compose-operations.md) |
| `ultronRecycler` | [RecyclerView operations](llm-views.md#recyclerview-lists) |
| `ultronBack` | Empty object `{}`; Android Back using the existing Ultron action |
| `ultronHideKeyboard` | Empty object `{}`; existing Ultron keyboard close action |
| `ultronElement` | Required `backend`, `using`, `value`, `action`; `text` for text input |
| `ultronAutomatorAct` | Version 2: `strategy`, `locator`, `action`; optional `text`, `packageName`, `index`, `timeoutMs` |
| `ultronAutomatorAssert` | Version 2: `strategy`, `locator`; optional `assertion`, `text`, `expected`, `packageName`, `index`, `timeoutMs` |
| `ultronWebAct` | Version 2: `webViewMatcher`, `locator`, `action`; optional `text`, `frames`, `timeoutMs` |
| `ultronWebAssert` | Version 2: `webViewMatcher`, `locator`; optional `assertion`, `text`, `frames`, `timeoutMs` |

`params` uses the existing driver JSON contract. It does not accept session IDs, MCP preflight
options or MCP named Espresso assertion aliases. For example, an Espresso displayed check uses
`condition: {class: 'ViewMatchers', name: 'isDisplayed'}`. Compose checks retain their existing
`assertion` field.

`ultronElement` accepts `backend: "espresso" | "compose"` explicitly so replay is independent
of the current driver setting. `using`/`value` are the existing native/Compose locator contract;
the element is found again at execution time. Actions are `find`, `click`, `clear`, `typeText`,
`replaceText`, `text`, `displayed`, `enabled`, `selected`. Reads return `value`, and lookup returns
`element`; `value: false` is a successful getter result, not a failed assertion. Mutations have
`value: null`. Use explicit Ultron assertions to check expected values.
Optional `requireUnique: true` fails before acting if the locator resolves to zero or multiple
elements. MCP element preflight enables this guard, which is retained in recorded scenarios.

These three operations are also available individually as `mobile: ultronBack`,
`mobile: ultronHideKeyboard` and `mobile: ultronElement`, using the same execution layer.
Individual calls collect snapshots when enabled; inside a batch only the failure snapshot is captured.

For example, navigation and locator-based input can be mixed with matcher assertions:

```json
{
  "schemaVersion": 1,
  "steps": [
    {"id": "back", "command": "ultronBack", "params": {}},
    {"id": "input", "command": "ultronElement", "params": {
      "backend": "compose", "using": "-android viewtag", "value": "query",
      "action": "replaceText", "text": "Example"
    }},
    {"id": "keyboard", "command": "ultronHideKeyboard", "params": {}}
  ]
}
```

All envelopes, parameter types, operation arguments and matcher factories are validated before
the first operation executes. An invalid later step rejects the whole request without running
earlier steps. A Compose step requires a Compose-enabled server; a RecyclerView step requires
RecyclerView support. Validation does not search the current UI: elements are resolved by each
operation when its turn arrives.

## Result

The result contains `schemaVersion`, `status` (`success` or `failure`), total `durationMs`,
`failedStepId` (`null` on success) and one result for every submitted step, in order:

| Step status | `operation` | `snapshot` |
| --- | --- | --- |
| `success` | Existing operation metadata | `null` |
| `failure` | Existing operation metadata, retaining the execution error | Snapshot selected by version and executor |
| `skipped` | `null` | `null` |

An operation failure is returned as a structured batch result over HTTP 200, as with existing
Ultron operation results. Inspect `result.status`; HTTP success alone does not mean the batch
passed. Invalid requests use the existing Appium error response.

Version 1 failure capture uses the session's `ultronSnapshotMode`. In version 2, Espresso,
Compose, RecyclerView and existing device operations use `mode: "ultron"`; Automator uses
`mode: "automator"`; Web uses the target WebView DOM with native context. None of these choices
changes the session setting. The response echoes the requested batch `schemaVersion`.

Failure capture follows the failed Compose step's `useUnmergedTree` option (default `true`);
Espresso and RecyclerView failures use the unmerged mixed tree. A partial or unavailable snapshot
does not replace the original execution error. No additional snapshots are collected during retries.
`ultronLastResult` stores the last executed step; successful batch steps have a disabled snapshot
there, while the failed step includes its failure snapshot.

## HTTP and MCP

Direct Android HTTP clients send the same request body to
`POST /session/:sessionId/appium/execute_mobile/ultron_batch`.

The standalone and Appium MCP interfaces expose `ultron_batch` with the same `steps` and optional
`schemaVersion`, plus the usual optional session selector. A failed batch sets MCP `isError: true`
and retains all step results and the failure snapshot. The tool sends one request, does not toggle
snapshot settings, and does not retry the batch.

Execution is synchronous. Set the client's HTTP/tool timeout to cover the entire batch, not just
one step; standalone MCP supports `--timeout-ms`. Requests through the Android HTTP router are
serialized for the duration of the batch. Other host/ADB clients can still change the application.

Completed actions remain applied after a later failure. A timeout or lost connection does not
cancel or roll back the batch and does not prove which actions completed. Observe the application
before deciding whether to repeat mutations. There are no batch-level retries, branches, loops,
or automatic resume.

MCP can [record individual operations](recording-scenarios.md) into this exact batch format.
Session creation/closure are preparation steps. Observation tools, snapshot settings and driver
selection are not UI scenario steps; recorded element operations carry their selected backend.

## Mixed Espresso, Automator and Web scenarios

```javascript
const result = await driver.execute('mobile: ultronBatch', {
  schemaVersion: 2,
  steps: [
    {id: 'open', command: 'ultronAct', params: {
      matcher: {class: 'ViewMatchers', name: 'withText', args: ['Open']},
      action: 'click', timeoutMs: 5000,
    }},
    {id: 'permission', command: 'ultronAutomatorAct', params: {
      strategy: 'res', locator: 'com.android.permissioncontroller:id/permission_allow_button',
      action: 'click', timeoutMs: 5000,
    }},
    {id: 'submit', command: 'ultronWebAct', params: {
      webViewMatcher: {class: 'ViewMatchers', name: 'withId', args: ['com.example:id/browser']},
      locator: {using: 'ID', value: 'submit'}, action: 'click', timeoutMs: 5000,
    }},
    {id: 'confirmation', command: 'ultronWebAssert', params: {
      webViewMatcher: {class: 'ViewMatchers', name: 'withId', args: ['com.example:id/browser']},
      locator: {using: 'ID', value: 'status'}, assertion: 'textEquals', text: 'Submitted',
      timeoutMs: 5000,
    }},
  ],
});
```

The four new commands are also available individually as `mobile: ultronAutomatorAct`,
`mobile: ultronAutomatorAssert`, `mobile: ultronWebAct`, and `mobile: ultronWebAssert`.
Direct HTTP uses `ultron_automator_act`, `ultron_automator_assert`, `ultron_web_act`, and
`ultron_web_assert` under `/session/:sessionId/appium/execute_mobile/`. MCP exposes tools with
these same snake case names. Single commands use the same preparation and execution as batch
steps; when automatic snapshots are enabled, they use their executor's diagnostic plan.

Automator strategies are `clazz`, `res`, `text`, `textContains`, `textStartsWith`, `textEndsWith`,
`desc`, `descContains`, `descStartsWith`, `descEndsWith`, and `pkg`, matching the legacy
`uiautomator` vocabulary. Optional `packageName` further constrains the selector. Targets must
be unique unless a zero-based `index` is supplied. Actions are `click`, `longClick`, `clearText`,
`replaceText` (requires `text`), and reads `text`, `enabled`, `checked`, `selected`, `displayed`.
Assertions are `exists` (default), `notExists`, `textEquals`, `textContains`, and the four boolean
properties. Text assertions require `text`; boolean assertions accept `expected` (default `true`).
`displayed` checks that the target has nonempty visible bounds. Missing action targets fail;
negative existence checks succeed only when the requested target is absent. Reads returning
`false` are successful operations. The legacy bulk `mobile: uiautomator` contract is unchanged.

Web operations require an Espresso `webViewMatcher` without `scope`, and a DOM locator
`{using, value}`. Locator names are `ID`, `CSS_SELECTOR`, `XPATH`, `CLASS_NAME`, `NAME`,
`TAG_NAME`, `LINK_TEXT`, and `PARTIAL_LINK_TEXT`. The WebView and positive DOM targets must be
unique. Actions are `click`, `clearText`, `typeText` (requires `text`), `text`, and `scrollIntoView`.
Assertions are `exists` (default), `notExists`, `textEquals`, and `textContains`; text assertions
require `text`. `frames` is an optional path of at most 16 entries, each exactly `{index: 0}` or
`{idOrName: "content"}`. Each entry selects a child of the preceding window. Each step resolves
the WebView and frame/element references afresh inside the Ultron retry boundary. There is no
cached `webviewEl` or replay of previously successful batch actions.

Web failure snapshots keep the native hierarchy and enrich only the uniquely matched WebView
with DOM, including accessible embedded frames. The host node has `diagnostic_target: true`;
`snapshot.context` records `backend: "web"`, the requested `frames`, and `webViewMatches`.
If the target WebView is missing or ambiguous, diagnostics are partial and do not substitute
another WebView's DOM. Inaccessible frame DOM is reported as partial by the existing collector.
All new commands accept `timeoutMs` from 0 to 60000 and retain Ultron's default when omitted.

MCP recordings automatically export version 2 after a mixed command or an explicitly version 2
batch, including batches containing only existing commands. Pure version 1 recordings retain
version 1. The full scenario is still validated before any step executes.
