---
hide:
  - navigation
  - toc

title: Welcome
---
<style>
  .md-typeset h1 {
    display: none;
  }
</style>

<div style="text-align: center">
  <img src="assets/images/appium-plus-espresso.png" style="max-width: 500px;" />
</div>

Welcome to the Ultron Omni Driver documentation! This is an independent fork of
[Appium Espresso Driver](https://github.com/appium/appium-espresso-driver), with
[Ultron](https://open-tool.github.io/ultron/docs/) operations, mixed View/Compose JSON snapshots
and MCP access for Android test clients and LLM agents. It is not an official Appium release.

## Why use this fork?

- Receive an operation result and the UI snapshot after it in one response, including execution
  failures, with automatic capture enabled.
- Observe Views and embedded Compose semantics together, and execute explicit View or Compose
  commands without switching the global subdriver.
- Use Ultron timeouts/retries, dedicated RecyclerView operations and extended Compose actions
  and assertions.
- Connect an agent through standalone MCP, with managed startup of a prebuilt instrumentation
  APK, or through an Appium MCP plugin for existing Appium deployments.

See the [comparison with Appium Espresso Driver](comparison.md) for details and limitations.
Espresso synchronization and basic Compose support are shared with upstream; instrumentation
signing and dependency compatibility requirements still apply.

## Explore the Documentation

<div class="grid cards" markdown>

-   Check out the [__Overview__](./overview.md) to learn how the driver works
-   Compare the [__fork's additions__](./comparison.md) with Appium Espresso Driver
-   Go through the [__Getting Started__](./getting-started/index.md) steps to get set up
-   Use the [__snapshot and View/RecyclerView guide__](./guides/llm-views.md) for observation and action APIs
-   Explore [__Ultron Compose operations__](./guides/compose-operations.md) for semantics actions and assertions
-   Run [__batch operations__](./guides/batch-operations.md) with snapshots only on the first failure
-   [__Record MCP test cases__](./guides/recording-scenarios.md) as reusable batch JSON scenarios
-   Follow [__MCP setup__](https://github.com/dabrynskiy/ultron-omni-driver/blob/master/packages/ultron-mcp/README.md) for standalone or Appium MCP use
-   Browse the [__Reference__](./reference/scripts.md) documentation for everything exposed by the driver
-   Read the different [__Guides__](./guides/usage-as-library.md) for a variety of instructions, tips and tricks
-   See the [__Troubleshooting__](./troubleshooting/index.md) section for a list of potential issues
-   For contributions to the driver, refer to the [__Contributing__](./contributing.md) page

</div>

## Related documentation

- [Ultron framework documentation](https://open-tool.github.io/ultron/docs/) and [source](https://github.com/open-tool/ultron)
- [Appium documentation](https://appium.io)
- [Upstream Espresso Driver documentation](https://appium.github.io/appium-espresso-driver/) for inherited behavior; this repository documents the fork's additions
