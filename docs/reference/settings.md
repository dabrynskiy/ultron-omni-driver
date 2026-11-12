---
title: Settings
---

The Espresso driver exposes various settings through Appium's [Settings API](https://appium.io/docs/en/latest/guides/settings/).

## `driver`

| Type | Default |
| -- | -- |
| `string` | `espresso` |

The name of the subdriver to use for interaction with elements. Supported values are `espresso` and
`compose`.

Switching the value to `compose` enables interactions with [Jetpack Compose](https://developer.android.com/compose)-based
application user interfaces. Refer to the [Jetpack Compose guide](../guides/compose.md) for more
details.

## `ultronSnapshotEnabled`

| Type | Default |
| -- | -- |
| `boolean` | `false` |

Enables automatic snapshots after supported Ultron operations. Snapshot selection follows the
rules under [`ultronSnapshotMode`](#ultronsnapshotmode); Compose semantics are available in the
`composeOn` flavor. Set it at runtime:

```javascript
await driver.updateSettings({ultronSnapshotEnabled: true});
// ... perform operations ...
await driver.updateSettings({ultronSnapshotEnabled: false});
```

`true` registers `UltronSnapshotListener`; `false` removes it and clears the last saved result.
The setting resets to `false` at server startup and when a session is created or deleted.
Only boolean values are accepted. Failed device updates are propagated to the client.

When disabled, ordinary operations do not collect snapshots. `mobile: ultronAct` and
`mobile: ultronAssert` still execute and return their operation result, with an empty snapshot whose
status is `disabled`; they do not enable the listener or collect a fallback dump. Explicit
`mobile: ultronSnapshot` remains available regardless of this setting. See the
[LLM View guide](../guides/llm-views.md) for the response contract.

## `ultronSnapshotMode`

| Type | Default |
| -- | -- |
| `string` | `ultron` |

Selects the observation provider: `ultron` reads Views and Compose semantics; `automator` reads
accessibility windows, including system UI. Both automatically add available DOM for in-process
WebViews. There is no DOM override option. This setting controls explicit snapshots without a
per-call `mode` override, ordinary post-operation snapshots, and batch failure snapshots with
`schemaVersion: 1` (the default). Individual `ultronAutomatorAct`/`ultronAutomatorAssert` and
`ultronWebAct`/`ultronWebAssert` commands, and batch failures with `schemaVersion: 2`, use diagnostics
selected by their executor without changing the session setting. See the
[batch guide](../guides/batch-operations.md#mixed-espresso-automator-and-web-scenarios) for those
diagnostics. Snapshot mode does not change action executors or locator semantics.

```javascript
await driver.updateSettings({ultronSnapshotMode: 'automator'});
// Override only this observation:
await driver.execute('mobile: ultronSnapshot', {mode: 'ultron'});
```

The mode resets to `ultron` at server startup and when a session is created or deleted. Invalid
values and failed device updates propagate to the client. In MCP, use `ultron_settings` with
`snapshotMode: "ultron" | "automator"`. See the [snapshot contract](../guides/llm-views.md#snapshot-contract).
