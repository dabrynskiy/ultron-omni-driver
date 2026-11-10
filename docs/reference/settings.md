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

Enables automatic mixed View/Compose hierarchy snapshots after Espresso Ultron operations
(Compose semantics are available in the `composeOn` flavor). Set it at runtime:

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
