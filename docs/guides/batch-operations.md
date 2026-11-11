---
title: Batch operations
---

# Batch operations

`mobile: ultronBatch` executes Espresso, Compose, RecyclerView, navigation and locator-based element operations
sequentially inside Android instrumentation, in one request. It stops at the first failed
operation and captures one mixed View/Compose snapshot for that step only. Successful and
skipped steps have `snapshot: null`.

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

The request accepts only `schemaVersion` (optional, defaults to `1`) and `steps` (required,
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
| `failure` | Existing operation metadata, retaining the execution error | Mixed View/Compose snapshot |
| `skipped` | `null` | `null` |

An operation failure is returned as a structured batch result over HTTP 200, as with existing
Ultron operation results. Inspect `result.status`; HTTP success alone does not mean the batch
passed. Invalid requests use the existing Appium error response.

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
