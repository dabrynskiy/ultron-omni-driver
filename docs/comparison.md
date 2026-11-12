---
title: Comparison with Appium Espresso Driver
---

Ultron Omni Driver is an independent fork of
[Appium Espresso Driver](https://github.com/appium/appium-espresso-driver). It adds Ultron
operations, mixed View/Compose observations and MCP access for test clients and LLM agents.
For Appium installation, the driver name remains `espresso` and `appium:automationName` remains
`Espresso`; see [Getting Started](getting-started/index.md).

## What the fork adds

| Area | Inherited Appium Espresso workflow | Ultron Omni Driver addition |
| --- | --- | --- |
| Action and observation | A standard action and a page-source request are separate commands. | Explicit Ultron operations return `{operation, snapshot}` with the UI state captured after execution, including execution failures. |
| Mixed screens | Standard View and Compose endpoints use the selected subdriver and separate element caches. | A JSON snapshot includes Views and embedded Compose semantics together, without changing `driver`. |
| Waiting and retries | Espresso/Compose synchronization and client-side waits remain available. | Ultron executes supported retries on Android, with per-operation `timeoutMs` on the explicit APIs. |
| RecyclerView | View matchers and generic actions remain available. | `ultronRecycler` exposes item search, scrolling, item/child actions and adapter count/existence checks. |
| Compose | Upstream already supports Compose locators, attributes and basic actions through its Compose subdriver. | Explicit Compose commands add Lazy scrolling, state/text assertions, IME, progress, selection, clipboard and other semantics actions without switching the global subdriver. |
| Agent connection | Appium clients connect through Appium Server. | Standalone MCP connects directly to Android instrumentation; an optional Appium MCP plugin uses remote WebDriver sessions. |

### Operation execution

[Ultron](https://open-tool.github.io/ultron/docs/) wraps Espresso, Compose Test APIs and UI
Automator with operation execution, a configurable retry policy and lifecycle listeners. The
fork uses these operations in its Android handlers and exposes explicit action/assertion APIs.
Supported temporary failures can be retried within the operation timeout. This can reduce
client-side polling, while retaining the underlying framework's synchronization.

The explicit View and Compose APIs accept `timeoutMs` from 0 to 60000 ms; zero makes a single
attempt. Only failures supported by the Ultron retry policy are retried. These extensions add no
outer transport retry loop. After a lost response to a mutation, observe the UI before deciding
whether to repeat it.

### Observe the result in the same response

Enable `ultronSnapshotEnabled: true` for direct Appium/HTTP calls. MCP action/assertion tools
enable automatic capture themselves. For example, after creating an Appium session:

```javascript
await driver.updateSettings({ultronSnapshotEnabled: true});
const result = await driver.execute('mobile: ultronAct', {
  matcher: {class: 'ViewMatchers', name: 'withText', args: ['Continue']},
  action: 'click',
  timeoutMs: 5000,
});
// Check result.operation.status, then inspect result.snapshot.status and roots.
```

The operation and snapshot have separate statuses: a snapshot collection failure does not
replace an action's result. Capture happens after the operation and its final retry; it does not
freeze asynchronous application work. Follow actions with assertions of the expected outcome.
Standard W3C commands keep their original return formats; `mobile: ultronLastResult` provides
the latest saved result separately when capture is enabled.

See the [snapshot and View/RecyclerView API](guides/llm-views.md),
[Compose operations](guides/compose-operations.md) and
[MCP setup](https://github.com/dabrynskiy/ultron-omni-driver/blob/master/packages/ultron-mcp/README.md)
for complete contracts.

## Shared requirements and limits

Espresso synchronization, IdlingResource, access to app internals and basic Compose support are
inherited capabilities. They are not differences from Appium Espresso Driver. Instrumentation
must still match the target application's signing certificate and dependencies. Standalone MCP
removes the Appium Server dependency at runtime, but still needs a compatible, prebuilt signed
instrumentation APK.

Mixed snapshots require `composeOn` to include Compose nodes. View operations target Views;
Compose operations target semantics nodes and require the active Compose test rule. The mixed
snapshot API defaults to the target app's Views and Compose semantics. Its explicit `automator` mode
reads accessibility windows, including system UI; both modes add available in-process WebView DOM.
External browser DOM is outside this API, and observing a source does not switch action executors.
RecyclerView assertions cover current adapter data; Compose absence checks
cover the current semantics tree, not every uncomposed Lazy item.

The combined response removes the need for a separate snapshot request after each explicit
operation. End-to-end speed, test reliability and model-call savings depend on the application
and client workflow; this comparison makes no measured performance or flakiness claim.

## Ultron framework resources

The framework's Kotlin API is broader than the remote API exposed by this fork. Use this
repository's guides to determine which operations are available over Appium or MCP.

- [Ultron documentation](https://open-tool.github.io/ultron/docs/) and [source](https://github.com/open-tool/ultron)
- [RecyclerView](https://open-tool.github.io/ultron/docs/android/recyclerview/)
- [Compose LazyList](https://open-tool.github.io/ultron/docs/compose/lazylist/)
- [Operation lifecycle listeners](https://open-tool.github.io/ultron/docs/common/listeners/)
- [Dependency management](https://open-tool.github.io/ultron/docs/intro/dependencies/)
