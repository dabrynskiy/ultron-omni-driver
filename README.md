# Ultron Omni Driver

**Observe, act, record and replay Android UI workflows — with AI agents or Appium tests.**

Automate Android Views and Jetpack Compose through [Ultron](https://open-tool.github.io/ultron/docs/),
Espresso and Compose Test APIs. Give an agent a structured UI tree, execute actions and assertions,
and turn a successful workflow into a reusable JSON scenario.

Connect directly through **standalone MCP, without Appium Server**, or use the driver with
**Appium 3** and an optional Appium MCP plugin.

[Get started with MCP](packages/ultron-mcp/README.md#standalone-managed-device-lifecycle-prebuilt-apk) ·
[Get started with Appium](docs/getting-started/index.md) ·
[Documentation](docs/index.md) ·
[Comparison with Appium Espresso Driver](docs/comparison.md)

## Why Ultron Omni Driver?

| Capability                           | What you gain                                                                                                                                                            |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Act and observe in one response**  | Get the operation result and the UI snapshot after it, including execution failures, without a separate tree request.                                                    |
| **Views + Compose in one JSON tree** | Inspect mixed screens and use explicit View or Compose operations without switching the global subdriver.                                                                |
| **Standalone MCP**                   | Connect an AI agent directly to Android instrumentation. Managed startup installs a prebuilt APK, forwards a port and opens a session; closing releases owned resources. |
| **Record and replay scenarios**      | Automatically capture MCP actions and assertions as reusable JSON, then replay them through MCP, Appium or direct HTTP.                                                  |
| **Batch execution**                  | Run up to 100 steps sequentially on Android in one request. Stop at the first failure and receive its UI snapshot.                                                       |
| **Ultron waiting and retries**       | Handle supported transient UI failures on Android within each operation's timeout, reducing client-side polling.                                                         |
| **RecyclerView operations**          | Find and scroll to items, act on child controls, and check adapter counts or item existence.                                                                             |
| **Extended Compose operations**      | Scroll Lazy containers, check text and state, invoke IME and semantics actions, and control selection or progress.                                                       |
| **Matcher diagnostics for agents**   | Check supported matchers and locators against a fresh or saved snapshot; optionally require a unique observed target before acting.                                      |

MCP action/assertion tools enable automatic snapshots. Appium and direct HTTP clients enable
`ultronSnapshotEnabled` once; standard WebDriver commands retain their return formats.
See the [API guides](#documentation) for each capability's supported operations.

## From an agent workflow to a reusable scenario

Explore the app with an agent, check the expected results, and save the executed steps directly.
The agent does not need to reconstruct a test from conversation history.

1. Prepare the app's initial state and call `ultron_recording_start` with `{"name":"Checkout"}`.
2. Execute the workflow through MCP actions and assertions; supported UI calls are recorded automatically.
3. Call `ultron_recording_finish` with `{"outcome":"passed"}` to export `scenario.json`.
4. Restore the initial state and pass the scenario to `ultron_batch` to replay it.

The same JSON also runs through Appium's `mobile: ultronBatch` or the direct Android HTTP API.
Replay executes the stored steps on Android without an agent choosing each action again. On a
step failure, execution stops and returns the failed step's mixed View/Compose snapshot.

Recording is available in both standalone MCP and the Appium MCP plugin. It captures commands
sent through these interfaces; exporting a scenario does not automatically verify its replay.

[Recording guide](docs/guides/recording-scenarios.md) ·
[Batch format and execution](docs/guides/batch-operations.md)

## Choose your connection

### Standalone MCP: connect an AI agent directly

**No Appium Server or Appium MCP host required.** Use an MCP client with Node.js 22+ and Android
SDK/ADB. Install the MCP package dependencies from this checkout:

```sh
npm --prefix packages/ultron-mcp ci
```

With a compatible, signed instrumentation APK and a [device configuration](packages/ultron-mcp/examples/device.json),
configure your MCP client to launch:

```sh
node /absolute/path/to/ultron-omni-driver/packages/ultron-mcp/bin/ultron-mcp.mjs \
  --bootstrap-config /absolute/path/device.json
```

Call `ultron_snapshot` to begin. Managed mode prepares the device on the first device tool call
and cleans up its instrumentation, port forward and device lock on close. It uses your prebuilt
APK; building and signing are preparation steps.

[Build the APK and configure your MCP client](packages/ultron-mcp/README.md#standalone-managed-device-lifecycle-prebuilt-apk).
Manual connection to an already running instrumentation server is also supported.

### Appium: use your existing test stack

Install this fork with Appium 3:

```sh
appium driver install --source=git https://github.com/dabrynskiy/ultron-omni-driver.git
```

The package is `ultron-omni-driver`; the Appium driver name remains `espresso` and
`appium:automationName` remains `Espresso`. Appium builds instrumentation for the target app.

After creating a session, perform an action and receive the result and updated UI tree:

```javascript
await driver.updateSettings({ ultronSnapshotEnabled: true });
const result = await driver.execute("mobile: ultronAct", {
  matcher: { class: "ViewMatchers", name: "withText", args: ["Continue"] },
  action: "click",
  timeoutMs: 5000,
});
// Check result.operation.status and result.snapshot.status separately.
// Inspect result.snapshot.roots, then assert the expected outcome.
```

[Appium setup and session creation](docs/getting-started/index.md) ·
[Optional Appium MCP plugin](packages/ultron-mcp/README.md#optional-appium-mcp-plugin)

## Requirements and scope

- Android 8.0+ (API 26), on an emulator or a real device with ADB access.
- Instrumentation and the target app must have matching signing certificates and compatible dependencies.
  Standalone managed mode expects the target app to be installed; published packages contain sources,
  so build and sign an instrumentation APK for your app first. JDK 17+ is required for building it.
- Mixed snapshots and explicit Compose operations require a Compose-enabled server (`composeOn`).
  Snapshots describe the target app's Views and Compose semantics; WebView DOM and other apps'
  accessibility trees are outside this snapshot API. Hybrid/WebView automation uses the Appium path.

[Full setup requirements](docs/getting-started/index.md) ·
[Capabilities and limitations](docs/comparison.md)

## Documentation

| Start here                 | Guide                                                                                                                                                                |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Understand the project     | [Overview and architecture](docs/overview.md) · [Comparison with upstream](docs/comparison.md)                                                                       |
| Connect an agent           | [Standalone MCP and Appium MCP plugin](packages/ultron-mcp/README.md)                                                                                                |
| Observe and interact       | [Snapshots, View and RecyclerView APIs](docs/guides/llm-views.md) · [Compose operations](docs/guides/compose-operations.md)                                          |
| Reuse workflows            | [Record scenarios](docs/guides/recording-scenarios.md) · [Run batches](docs/guides/batch-operations.md)                                                              |
| Configure Appium           | [Getting started](docs/getting-started/index.md) · [Capabilities and build config](docs/reference/capabilities.md) · [Settings](docs/reference/settings.md)          |
| Look up commands           | [Execute methods](docs/reference/execute-methods.md) · [Locators](docs/reference/locator-strategies.md) · [Element attributes](docs/reference/element-attributes.md) |
| Integrate and troubleshoot | [Embed as a library](docs/guides/usage-as-library.md) · [Parallel tests](docs/guides/parallel-tests.md) · [Troubleshooting](docs/troubleshooting/index.md)           |
| Contribute                 | [Development, builds and tests](docs/contributing.md) · [Report an issue](https://github.com/dabrynskiy/ultron-omni-driver/issues)                                   |

## Built on Ultron and Appium Espresso

[Ultron](https://github.com/open-tool/ultron) supplies operation execution, supported retries and
lifecycle listeners over Espresso, Compose Test APIs and UI Automator.
Explore the [Ultron framework documentation](https://open-tool.github.io/ultron/docs/) for its Kotlin APIs.

This project is an independent fork of [Appium Espresso Driver](https://github.com/appium/appium-espresso-driver).
Espresso synchronization, IdlingResource and basic Compose support are inherited from upstream;
see the [comparison](docs/comparison.md) for this fork's additions and the
[upstream documentation](https://appium.github.io/appium-espresso-driver/) for inherited behavior.

Licensed under [Apache 2.0](LICENSE). This is not an official Appium release. Upstream copyright
and license notices are retained; see [NOTICE](NOTICE).
