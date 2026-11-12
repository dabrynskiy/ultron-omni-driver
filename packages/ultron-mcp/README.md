# Ultron MCP

Part of the independent [Ultron Omni Driver fork](https://github.com/dabrynskiy/ultron-omni-driver).
It exposes [Ultron](https://open-tool.github.io/ultron/docs/) operations and unified JSON snapshots:
Views/Compose by default, accessibility windows in `automator` mode, and automatic embedded WebView
DOM in both modes. See the [comparison with Appium Espresso Driver](../../docs/comparison.md)
for the benefits, inherited capabilities and limitations, and the
[architecture](../../docs/overview.md#end-to-end-architecture) for both connection paths.

Snapshot password fields and sensitive Compose semantics are redacted as described in the
[snapshot guide](../../docs/guides/llm-views.md). Use test data for ordinary app content, which is
returned to the MCP client as displayed. Published packages do not include local instrumentation
APKs; build and sign one for your target app using the instructions below.

The default standalone mode connects directly to the Android instrumentation HTTP server:

```text
MCP client / Inspector -> ultron-mcp -> adb forward -> Android HTTP server -> Views / Compose
```

It requires Node.js 22+ and Android SDK/ADB. **Neither Appium Server nor Appium MCP is
required.** Use a prebuilt, signed instrumentation APK with managed startup below, or prepare
Android manually. JDK 17+ is needed only to build the APK. The optional Appium MCP plugin
remains available for users who already run Appium.

## Standalone: managed device lifecycle (prebuilt APK)

Install the MCP package dependencies once:

```sh
npm --prefix packages/ultron-mcp ci
```

Copy [examples/device.json](examples/device.json) and set your already built and signed **instrumentation APK**:

```json
{
  "deviceSerial": "emulator-5554",
  "driverApk": "/absolute/path/ultron-omni-driver.apk",
  "appPackage": "com.example.app",
  "appActivity": ".MainActivity",
  "autoLaunch": true
}
```

The target app must already be installed. The APK must target this `appPackage`, have a compatible
signing certificate, and include dependencies compatible with the target app. The target app needs
INTERNET permission. Managed startup does not build, sign, uninstall or clear app data. It installs
the driver with `adb install -r`; an incompatible installed certificate is reported as an error.

Point your MCP client at this configuration:

```json
{
  "mcpServers": {
    "ultron": {
      "command": "node",
      "args": [
        "/absolute/path/to/ultron-omni-driver/packages/ultron-mcp/bin/ultron-mcp.mjs",
        "--bootstrap-config", "/absolute/path/device.json"
      ]
    }
  }
}
```

Call `ultron_snapshot` directly. Before the first device tool, MCP selects and locks the device,
checks for existing instrumentation, installs the driver once per MCP process, allocates a local
port using `adb forward tcp:0 tcp:6791`, starts instrumentation, waits for `/status` with the correct
target package, and creates/selects the Android HTTP session. Simultaneous first calls share this
preparation through the standalone command queue. No separate session-create or ADB command is needed.

Discovery (`initialize`, `tools/list`), invalid tool arguments, and `ultron_check_matcher` with a saved
snapshot do not prepare the device. In managed mode `ultron_session` supports:

| Action | Behavior |
| --- | --- |
| `status` | Local state (`idle`, `preparing`, `ready`, `failed`), selected device, installation flag, dynamic URL and session ID; never starts Android |
| `create` | Explicitly prepare using configured defaults; optional `appActivity` and `autoLaunch` overrides; `appPackage` must match the configured target |
| `close` | Stop owned instrumentation, remove its forward and release the device lock; safe before startup and when already closed |

After `close`, the next device call starts instrumentation and a new session without reinstalling
the APK. A new MCP process installs again. This is a process lifecycle, not a chat lifecycle.
External-session `attach` is available only in manual `--url` mode. A different MCP process or
active instrumentation is rejected instead of being replaced automatically.

Closing MCP stdin or receiving SIGINT/SIGTERM also cleans up owned resources. Closing a managed
session may force-stop the target app to ensure Android instrumentation is stopped; killing the
host ADB child alone is insufficient. The driver APK remains installed. Cleanup errors are reported;
call `close` again to retry after restoring device connectivity. An uncatchable kill (SIGKILL), host
crash or disconnected device can prevent cleanup. A stale device lock is deliberately retained:
the error gives its path, and the file contains the owning PID. Verify the owner and any remaining
instrumentation have stopped before removing it. The manager does not take over orphaned sessions.

Startup failures roll back owned resources and enter `failed`. Inspect the error and call `close`
before retrying. Loss of a UI-command response never retries the action or silently restarts the
app; observe the UI before deciding what to do next. A stopped instrumentation child is reported
on the next call/status. Runtime HTTP failures are returned directly.

Configuration fields (unknown fields are rejected):

| Field | Default / purpose |
| --- | --- |
| `driverApk` | Required; readable prebuilt instrumentation APK; relative paths resolve against `device.json` |
| `appPackage` | Required; installed target application package |
| `deviceSerial` | Optional only when exactly one authorized device is connected; the selected device is retained for the process lifetime |
| `appActivity`, `autoLaunch` | Activity optional, `autoLaunch=false`; activity is required when true |
| `adbPath` | Explicit executable/path; otherwise `$ANDROID_HOME` / `$ANDROID_SDK_ROOT` platform-tools, then `adb` on PATH |
| `instrumentationComponent` | `io.appium.espressoserver.test/androidx.test.runner.AndroidJUnitRunner` |
| `testClass` | `io.appium.espressoserver.EspressoServerRunnerTest` |
| `devicePort` | `6791`; local forwarded port is always allocated dynamically |
| `startupTimeoutMs` | `60000`; HTTP readiness wait after instrumentation launch |
| `installTimeoutMs` | `120000`; APK installation timeout |
| `adbTimeoutMs` | `15000`; other startup ADB commands |
| `expectedServerVersion` | Optional exact match against `/status` build version |

`--bootstrap-config` and `--url` are mutually exclusive. `--timeout-ms` still controls normal HTTP
commands (default 75000 ms). The first MCP tool can take installation time + startup time + session
creation; configure your MCP client's tool timeout accordingly. Cancellation during preparation
aborts startup and cleans up before any requested UI operation is dispatched. Device subprocess
diagnostics go to stderr, preserving MCP stdout exclusively for protocol messages.

## Standalone: build and start Android

From the repository root, install only this MCP package's dependencies:

```sh
npm --prefix packages/ultron-mcp ci
```

Build the server for your application's package (replace `com.example.app`):

```sh
cd espresso-server
./gradlew :app:assembleAndroidTest -PappiumTargetPackage=com.example.app
cd ..
```

The default is `composeOn`, including mixed View/Compose snapshots. To use the application's
signing key, add these Gradle properties to that build command:

```text
-PappiumKeystoreFile=/absolute/path/app-signing.jks
-PappiumKeystorePassword=STORE_PASSWORD
-PappiumKeyAlias=APP_KEY_ALIAS
-PappiumKeyPassword=KEY_PASSWORD
```

The target app and instrumentation APK must have the same signing certificate. Without signing
overrides, Gradle uses the local Android debug keystore. The target app needs INTERNET permission
and compatible AndroidX/Compose dependencies. Install the app APK if it is not already installed,
then install **only the instrumentation APK** from this project (not its dummy `app-debug.apk`):

```sh
adb -s <serial> install -r /absolute/path/app.apk
adb -s <serial> install -r espresso-server/app/build/outputs/apk/androidTest/debug/ultron-omni-driver.apk
adb -s <serial> forward tcp:8300 tcp:6791
adb -s <serial> shell am instrument -w \
  -e class io.appium.espressoserver.EspressoServerRunnerTest \
  io.appium.espressoserver.test/androidx.test.runner.AndroidJUnitRunner
```

Leave the instrumentation command running. If embedding the library into your own `androidTest`,
use your runner/component instead; the same HTTP/MCP connection works.

## Standalone: connect MCP manually

In another terminal, open MCP Inspector:

```sh
npx --yes @modelcontextprotocol/inspector \
  node /absolute/path/to/ultron-omni-driver/packages/ultron-mcp/bin/ultron-mcp.mjs \
  --url http://127.0.0.1:8300
```

Connect, list tools, and call `ultron_session`:

```json
{
  "action": "create",
  "appPackage": "com.example.app",
  "appActivity": ".MainActivity",
  "autoLaunch": true
}
```

`autoLaunch` defaults to false, useful when the app activity is already open. `action: "status"`
lists device sessions. If a session already exists, select it with
`{"action":"attach","sessionId":"existing-id"}`. There is one selected session per standalone
process. `ultron_session close` stops the Android HTTP server; restart `am instrument` before
creating another session. Disconnecting MCP itself leaves the Android session running.

MCP client configuration:

```json
{
  "mcpServers": {
    "ultron": {
      "command": "node",
      "args": ["/absolute/path/to/ultron-omni-driver/packages/ultron-mcp/bin/ultron-mcp.mjs", "--url", "http://127.0.0.1:8300"]
    }
  }
}
```

Standalone additionally provides:

| Tool | Purpose |
| --- | --- |
| `ultron_session` | Status, create, attach, close |
| `ultron_screenshot` | Screenshot returned as a PNG MCP image |

Back, keyboard and element tools are shared with the Appium plugin. For Compose, use an explicit backend (or select `driver=compose` with `ultron_settings`):

```json
{"backend":"compose","using":"-android viewtag","value":"submit_button","action":"click"}
```

Pass this to `ultron_element`. It resolves the locator afresh through the selected backend and returns operation metadata,
`element`/`value` and a mixed snapshot. The same parameters can be replayed as `ultronElement`. `ultron_act` and `ultron_assert` remain explicit Espresso View
operations. Boolean reads from `ultron_element` are values, not assertions of test success.

All standalone tool calls are serialized. Direct HTTP requests have a configurable 75-second
timeout (`--timeout-ms`) and no automatic retries, including on transport loss.

## Optional: Appium MCP plugin

A separate package in this repository, loaded by the public [Appium MCP plugin API](https://github.com/appium/appium-mcp#-plugin-api).
It uses `core.getDriver(sessionId)` and the remote client's public WebDriver commands. It adds no
MCP dependencies to the driver and does not manage devices or create/delete sessions itself.

### Run with Appium MCP

Requires Node.js 22+, Appium MCP 1.99.2, and an Appium 3 server using **this checkout's** Espresso
driver, including the Ultron extensions. Build/install that driver before creating the session.

From the repository root:

```sh
npm --prefix packages/ultron-mcp ci
npx --yes appium-mcp@1.99.2 --plugin /absolute/path/to/ultron-omni-driver/packages/ultron-mcp/index.mjs
```

MCP client configuration (replace the absolute path):

```json
{
  "mcpServers": {
    "ultron": {
      "command": "npx",
      "args": ["--yes", "appium-mcp@1.99.2", "--plugin", "/absolute/path/to/ultron-omni-driver/packages/ultron-mcp/index.mjs"],
      "env": {"NO_UI": "true"}
    }
  }
}
```

Use the host's `appium_session_management` tool to create a **remote** session. `platform: "general"`
passes explicit capabilities through, avoiding embedded UiAutomator2 selection:

```json
{
  "action": "create",
  "platform": "general",
  "remoteServerUrl": "http://127.0.0.1:4723",
  "capabilities": "{\"platformName\":\"Android\",\"appium:automationName\":\"Espresso\",\"appium:udid\":\"emulator-5556\",\"appium:app\":\"/absolute/path/to/app.apk\"}"
}
```

Alternatively attach an existing remote session with the host's `action: "attach"`. Pass its
`sessionId` on subsequent tools, especially when the host tracks several sessions. The plugin
supports remote WebDriver sessions; the host's embedded UiAutomator2/XCUITest sessions do not
provide these driver extensions.

## Tools

| Tool | Purpose |
| --- | --- |
| `ultron_check_matcher` | Check snapshot cardinality for Espresso, Compose, UIAutomator or DOM locators, with candidate details |
| `ultron_snapshot` | One JSON tree: `mode: "ultron"` (default Views/Compose) or `"automator"` (accessibility windows); automatic in-process WebView DOM; optional `maxDepth`, `maxNodes`, `useUnmergedTree` |
| `ultron_act` | Espresso View action by matcher; supports clicks, swipes, text, key events and keyboard close; returns operation + snapshot |
| `ultron_assert` | Named Ultron checks or custom View conditions; returns operation + snapshot |
| `ultron_compose_act` | Compose actions, Lazy scrolling, IME, semantics actions, selection and clipboard; returns operation + snapshot |
| `ultron_automator_act` / `ultron_automator_assert` | Ultron UiAutomator actions/checks with accessibility diagnostics; version 2 replay |
| `ultron_web_act` / `ultron_web_assert` | Ultron Web Atom actions/checks with WebView/frame locators and target DOM diagnostics; version 2 replay |
| `ultron_compose_assert` | Compose states, text/descriptions, ranges, sizes and matcher assertions; returns operation + snapshot |
| `ultron_recycler` | RecyclerView item search, scrolling, item/child actions, count and existence assertions; returns operation + snapshot |
| `ultron_batch` | Execute matcher, RecyclerView, navigation and element operations sequentially; stop at the first failure and capture only its snapshot |
| `ultron_back` | Android Back through Ultron; replayable and recordable |
| `ultron_hide_keyboard` | Close keyboard through Ultron; replayable and recordable |
| `ultron_element` | Resolve a locator in an explicit or currently selected backend; act/read without saving cached IDs |
| `ultron_recording_start` | Start automatic recording, with a test-case name and optional session selector |
| `ultron_recording_status` | Inspect recording progress, issues and saved draft paths without starting Android |
| `ultron_recording_finish` | Export a passed recording, or retain a failed draft |
| `ultron_recording_discard` | Stop recording and retain artifacts marked discarded |
| `ultron_last_result` | Latest saved Espresso or Compose result or null; not a command journal |
| `ultron_settings` | Read settings; optionally set `snapshotEnabled`, `snapshotMode: "ultron" / "automator"` or `driver: "espresso" / "compose"` |

`ultron_snapshot` accepts `mode: "ultron" | "automator"` for one observation. Omit it to use the
session default, initially `ultron`; change that default with `ultron_settings` and
`snapshotMode: "automator"`. The setting also controls ordinary post-operation snapshots and batch
failure snapshots with `schemaVersion: 1` (the default). Individual Automator/Web commands and
batch failures with `schemaVersion: 2` use diagnostics selected by their executor without changing
the session setting. The setting resets when the session ends and does not change action executors.
Both modes append available DOM for WebViews in the instrumented app; there is no DOM override flag
or external browser DOM support. Nodes identify their source, and missing DOM yields partial-result
diagnostics. See the
[snapshot contract](../../docs/guides/llm-views.md#snapshot-contract) for fields and limits.

In Appium MCP mode, screenshot, gestures and session management remain the host's
built-in tools. Use the Ultron Back/keyboard/element tools when those actions must be recorded. The plugin names do not override built-in Appium MCP tools.

For each individual action/assertion the plugin checks the current setting and enables automatic snapshots
if necessary. It preserves operation and snapshot statuses independently. Assertion/action failures
set MCP `isError: true` and retain the full operation and snapshot. Partial observations remain
usable; unavailable explicit snapshots are MCP errors. `structuredContent` and JSON text contain
the same response (null last-result is text only).

`ultron_batch` accepts `steps: [{id, command, params}]` and optional `schemaVersion: 1 | 2` (default `1`).
Version 2 adds `ultronAutomatorAct`, `ultronAutomatorAssert`, `ultronWebAct`, and `ultronWebAssert`.
The same operations are exposed as individual tools `ultron_automator_act`,
`ultron_automator_assert`, `ultron_web_act`, and `ultron_web_assert`. Automator returns accessibility
diagnostics; Web returns the target WebView DOM and native context. Version 2 chooses failure
diagnostics from the failed step; version 1 keeps the session observation mode. Recordings promote
to version 2 when these commands or an explicit version 2 batch are recorded. See the
[mixed batch contract](../../docs/guides/batch-operations.md#mixed-espresso-automator-and-web-scenarios)
for selectors, frames, assertions, timeouts, and examples.
Version 1 commands are `ultronAct`, `ultronAssert`, `ultronComposeAct`, `ultronComposeAssert` and
`ultronRecycler`, `ultronBack`, `ultronHideKeyboard` and `ultronElement`; params use the raw driver JSON API. Element steps require an explicit backend. Espresso batch assertions use
`condition`/`exists`, rather than MCP named assertion aliases. The tool does not change snapshot
settings: successful/skipped steps have no snapshot, and only the first failed step receives one,
including when automatic snapshots are disabled. See the [batch guide](../../docs/guides/batch-operations.md)
for examples, validation, results and transport timeout behavior.

There are no outer retries. After a lost mutation response, observe the UI before deciding to repeat
the action. Remote WebDriver transport retry configuration is controlled by the host. Calls from
this plugin are serialized per selected driver; other clients/host tools can still change the UI.

## Record a reusable scenario

1. Prepare the session and initial app state.
2. Call `ultron_recording_start` with `{"name":"Checkout"}`.
3. Execute individual UI operations and explicit assertions normally.
4. Call `ultron_recording_finish` with `{"outcome":"passed"}`.

The finish result returns `scenario`, directly consumable by `ultron_batch`, plus `scenarioPath`,
`draftPath` and `journalPath`. Commands are recorded after MCP argument normalization. Element
steps contain locators and the selected backend, not cached IDs. Element getters/lookups are
also retained because finding a locator can scroll; their values are not assertions. Other
observations are journaled but not replayed; failed/unknown operations block passed export and are not silently removed.
Use `outcome: "failed"` or `ultron_recording_discard` to retain an unsuccessful draft.

Files are saved under `.ultron-recordings` in the process working directory by default. Set
`ULTRON_RECORDINGS_DIR`, or standalone `--recordings-dir /path/scenarios`, to choose a destination.
Input values are stored literally. Recording is not automatic replay verification; reproduce
initial state before reusing a scenario. See [Recording scenarios](../../docs/guides/recording-scenarios.md)
for persistence, session boundaries and error behavior.

## Execute a test case

1. Create/attach the remote session with an explicit device ID.
2. Call `ultron_snapshot` and select a unique target from the observed properties.
3. Call `ultron_act`, then inspect both `operation.status` and `snapshot.status`.
4. Call `ultron_assert` for the expected result. Record this evidence as the outcome of the step.
5. Use the host's screenshot tool for visual details absent from the tree.

Example `ultron_act` arguments:

```json
{
  "sessionId": "your-session-id",
  "matcher": {
    "class": "LlmViewMatchers",
    "name": "propertyEquals",
    "args": ["resourceId", "com.example:id/submit"]
  },
  "action": "click",
  "timeoutMs": 5000
}
```

Example `ultron_assert` arguments:

```json
{
  "sessionId": "your-session-id",
  "matcher": {"class": "ViewMatchers", "name": "withText", "args": ["Saved"]},
  "assertion": "isDisplayed",
  "timeoutMs": 5000
}
```

### RecyclerView

`ultron_recycler` works in standalone and Appium MCP plugin modes with an updated Android server
(and an updated Appium driver in plugin mode). Select the list with `matcher`, then supply exactly
one of `position` (zero-based outer-adapter position) or `itemMatcher` (matches the item root).
Use `hasDescendant` to find a row by text, and `childMatcher` to select a button or input within it:

```json
{
  "matcher": {"class":"LlmViewMatchers","name":"propertyEquals","args":["resourceId","com.example:id/feed"]},
  "action": "click",
  "itemMatcher": {"class":"ViewMatchers","name":"hasDescendant","args":[
    {"class":"ViewMatchers","name":"withText","args":["Article title"]}
  ]},
  "childMatcher": {"class":"ViewMatchers","name":"withContentDescription","args":["Open"]},
  "searchLimit": 500,
  "timeoutMs": 5000
}
```

Actions: `scrollToItem`, `click`, `longClick`, `doubleClick`, `typeText`, `replaceText`, `clearText`,
`assertItem` (requires `condition`), `assertItemExists`, `assertItemNotExists`, `assertItemCount`.
`assertItemCount` requires `itemCount` instead of an item selector. Text actions require `text`.
`childMatcher` is available for clicks, text actions and `assertItem`. Only `matcher.scope` may
select a window. `preflight: true` checks uniqueness of the list, not off-screen adapter items.

Item interactions scroll first and require a unique item/child. Existence checks accept any
match and do not scroll. Searches bind off-screen ViewHolders; use content matchers, and account
for application binding code being invoked. `searchLimit` (1..10000, default 1000) bounds adapter
positions per attempt. An incomplete search fails when absence or uniqueness cannot be established.
Assertions cover current adapter data, not future unloaded pages. Position is absolute for
ConcatAdapter; it differs from the child index in the dump. A failed operation is an MCP error
with a snapshot. After transport loss, observe before deciding to repeat an action.

Snapshots expose `is_recycler_view` for custom subclasses, adapter count/LayoutManager/stable-ID
support on lists, and adapter/binding positions, view type and stable ID on attached item roots.
Stable IDs are decimal strings. These fields are supported by `ultron_check_matcher`, as is
`ViewMatchers.isAssignableFrom` for `androidx.recyclerview.widget.RecyclerView`; old dumps lacking
the runtime flag are indeterminate. See the [full RecyclerView contract](../../docs/guides/llm-views.md#recyclerview-lists)
for field names and limitations.

### View actions

`ultron_act` supports `click`, `doubleClick`, `longClick`, `typeText`, `replaceText`, `clearText`,
`scrollTo`, `swipeUp`, `swipeDown`, `swipeLeft`, `swipeRight`, `pressKey`, `closeSoftKeyboard`,
`clickTopLeft`, `clickTopCenter`, `clickTopRight`, `clickCenterRight`, `clickBottomRight`,
`clickBottomCenter`, `clickBottomLeft`, and `clickCenterLeft`. These actions are available in both
standalone and Appium MCP plugin modes.

Positional clicks accept integer pixel offsets, defaulting to zero. Corner clicks accept `offsetX`
and `offsetY`; top/bottom-center accept only `offsetY`; center-left/right accept only `offsetX`.
Positive X moves right, positive Y moves down; choose inward offsets near edges. Unsupported offset
axes and key parameters on other actions are rejected before dispatch.

```json
{
  "matcher": {"class":"ViewMatchers","name":"withText","args":["Continue"]},
  "action": "clickTopRight",
  "offsetX": -12,
  "offsetY": 12,
  "name": "Continue button",
  "timeoutMs": 5000
}
```

For `pressKey`, supply `keyCode`; optionally add non-negative integer `metaState` and `flags`
bitmasks (for example `keyCode: 29, metaState: 1` sends Shift+A). The key code must be greater than
zero and below the device's maximum key code, following Espresso. Key events go to the current
focus; the matcher does not focus an input automatically. Click the intended input first.

Optional `autoScroll` overrides Ultron's auto-scroll setting for one action. Omit it to keep the
configured default. It follows Ultron's behavior: positional clicks always use their custom
scrolling action. Optional non-empty `name` labels the element in operation diagnostics. All new
actions preserve Ultron waiting and the operation/snapshot response contract. See the
[driver action reference](../../docs/guides/llm-views.md#observe-and-act) for details.

The snapshot includes Compose semantics in `composeOn`. `ultron_act` / `ultron_assert` still target
Android Views. `ultron_settings` can select Compose for standard Appium source/find/action
commands; it does not change those two explicit Espresso executors. Compose operations do not
currently trigger the Espresso snapshot listener, so request `ultron_snapshot` afterwards.
See the [snapshot contract](../../docs/guides/llm-views.md).

## Named assertions

In both standalone and Appium MCP plugin modes, `ultron_assert` accepts an optional `assertion`
name. Named checks compile to the existing `exists`/`condition` driver arguments, preserving Ultron
waiting, window scope, timeout, failure reporting and the operation + snapshot response. They run
against live Android Views; no snapshot preflight or local boolean evaluation replaces the assertion.
This MCP convenience layer requires no Android APK update when `ultronAssert` is already supported.

| `assertion` | Additional arguments |
| --- | --- |
| `exists`, `doesNotExist` | None |
| `isDisplayed`, `isNotDisplayed`, `isVisible`, `isCompletelyDisplayed` | None |
| `isDisplayingAtLeast` | `percentage`: integer 0..100 |
| `isEnabled`, `isNotEnabled`, `isSelected`, `isNotSelected` | None |
| `isClickable`, `isNotClickable`, `isChecked`, `isNotChecked` | None |
| `isFocusable`, `isNotFocusable`, `hasFocus`, `isJavascriptEnabled` | None |
| `hasText`, `hasContentDescription` | Exactly one of `text` or `resourceId` |
| `textContains`, `contentDescriptionContains` | `text` |

`text` may be empty. `resourceId` is a positive 32-bit Android string resource ID, resolved on the
device. For Matcher overloads or more complex checks, continue using `condition` with nested
Espresso/Hamcrest matchers.

```json
{
  "matcher": {"class":"ViewMatchers","name":"withId","args":[2131230890]},
  "assertion": "hasText",
  "text": "Saved",
  "timeoutMs": 3000
}
```

Other examples: `"assertion":"isNotEnabled"`, `"assertion":"doesNotExist"`,
`"assertion":"hasContentDescription","resourceId":17039370`, or
`"assertion":"isDisplayingAtLeast","percentage":90`, each alongside the target `matcher`.

`isNotDisplayed` requires the View to exist in the hierarchy; use `doesNotExist` for absence.
`isVisible` checks effective visibility `VISIBLE`, which differs from screen display.
`isNotChecked` uses Espresso's Checkable-specific matcher; `hasFocus` includes focused descendants.
These checks preserve the corresponding Ultron Espresso semantics.

Choose one mode per call: a named `assertion`, a custom `condition`, or legacy `exists`.
Combining `assertion` with `condition` or `exists` is rejected, even when the values agree.
Missing arguments and parameters unrelated to the chosen assertion are rejected before any device
call. Without `assertion`, existing `condition`/`exists` calls remain supported; omitting all of them
still checks existence. Keep window selection in `matcher.scope`.

Assertion failures return MCP `isError: true` and retain the full runtime operation and snapshot.
Named checks add no outer retries. Restart the MCP process to load the new schemas.

## Check a matcher before acting

`ultron_check_matcher` obtains a fresh dump (100 depth, up to 10000 total nodes), or
accepts an optional saved `snapshot` and works offline without a session. It evaluates the observed
tree, including hidden nodes; Views, semantics, accessibility and DOM representations are counted
separately. Fresh `backend: "automator"` checks request `mode: "automator"`; other backends request
`mode: "ultron"` with unmerged Compose semantics, regardless of the session's observation setting.
DOM checks also accept saved accessibility snapshots containing DOM.

View example:

```json
{
  "backend": "espresso",
  "matcher": {"class":"LlmViewMatchers","name":"propertyEquals","args":["resourceId","com.example:id/submit"]}
}
```

Compose example:

```json
{"backend":"compose","using":"-android viewtag","value":"submit_button"}
```

UIAutomator examples (exact accessibility fields, not Espresso View properties):

```json
{"backend":"automator","using":"text","value":"Allow","scope":{"windowId":7}}
```

```json
{"backend":"automator","using":"-android uiautomator","value":"new UiSelector().resourceId(\"com.example:id/submit\").enabled(true)"}
```

Supported accessibility locators: `id` (literal full resource ID), exact `text`, `accessibility id`,
and exact `class name`. `-android uiautomator` accepts a single `UiSelector()` chain with exact string
predicates `text`, `description`, `resourceId`, `className`, `packageName`, and boolean predicates
`enabled`, `focused`, `focusable`, `clickable`, `longClickable`, `selected`, `checkable`, `checked`,
`scrollable`. Strings use JSON escaping. Repeated predicates, regex/substring factories, positional
selectors, nested selectors and arbitrary Java are `unsupported`; supplied code is never executed.
All accessibility windows are counted unless `scope.windowId` selects one. UIAutomator locators
require an accessibility snapshot; Espresso/Compose matchers still reject that snapshot.

DOM example:

```json
{"backend":"dom","using":"css selector","value":"button[aria-label=\"Save\"]","scope":{"webview":"/roots/0/children/2","context":[]}}
```

`scope.webview` is the WebView host's JSON path in this snapshot. Omit it to count matches across
WebViews. `scope.windowId` optionally narrows the native window when its ID is available.
DOM `scope.context` defaults to `[]`, meaning top documents only. To check inside a frame or open
shadow root, copy the target node's complete `context` array, e.g.
`[{"type":"frame","selector":"html > body:nth-of-type(1) > iframe:nth-of-type(1)"}]`.
CSS relationships never cross frame/shadow boundaries. Matches include `webview`, `context`,
`selector`, `tagName` and saved `attributes`, so duplicate selectors in separate documents remain
distinguishable. WebView paths and context selectors describe the snapshot, not durable handles.

DOM supports `id` and a bounded `css selector` grammar: tag names, `*`, escaped `#id`, saved attribute
presence/equality (`id`, `name`, `type`, `role`, `aria-label`, `placeholder`), child `>` and descendant
combinators, and positive integer `:nth-of-type(n)`, including paths emitted by the collector.
Class selectors, unsaved attributes, selector lists, other combinators/pseudo-classes and XPath are
`unsupported`. The collector records namespaces and document mode for CSS tag/ID case matching;
older dumps with unknown metadata, or HTML type comparisons requiring additional case rules,
produce `indeterminate` when case changes could affect a match. Redacted,
missing or potentially truncated matching values cannot prove a match or its absence.
Counts describe the collected UI DOM; omitted script/style/template content and closed shadow
trees are outside this observation.

For actions and assertions, use `ultron_act`/`ultron_assert` for Espresso,
`ultron_compose_act`/`ultron_compose_assert` for Compose,
`ultron_automator_act`/`ultron_automator_assert` for UIAutomator, and
`ultron_web_act`/`ultron_web_assert` for Web Atoms. The `ultron_element` tool accepts
`backend: "espresso" | "compose"`.

To verify against a real Android WebView and accessibility tree, build and install the library test
APK, then run the opt-in fixture on an explicitly selected device:

```sh
cd espresso-server
./gradlew :library:assembleComposeOnDebugAndroidTest
cd ..
adb -s emulator-5556 install -r -t espresso-server/library/build/outputs/apk/androidTest/composeOn/debug/library-composeOn-debug-androidTest.apk
npm --prefix packages/ultron-mcp run test:locators:android -- --serial emulator-5556 --output /private/tmp/ultron-locator-device
```

The runner compares MCP counts with actual `UiDevice`/`querySelectorAll` counts, verifies frame/shadow
contexts, real truncated dumps and redaction, and saves snapshots, reports and the instrumentation
log. It starts a dedicated test activity/session and removes its ADB forwarding and fixture on exit.
It requires no Appium server. The Android fixture is skipped in normal test runs unless explicitly
enabled by this runner.

The result contains `status`, `observedCount`, `exact`, `snapshotId`, `capturedAtMs`, `reasons`,
and up to 20 matching nodes (`limit` can be 1..100). Candidates retain their dump path, window,
identity, bounds and visibility; semantics candidates also include config. `matchesTruncated`
describes only the candidate list, independently of whether the dump was truncated.

| Status | Meaning |
| --- | --- |
| `unique` | Exactly one match in the complete supported observation |
| `not_found` | Zero matches in the complete supported observation |
| `ambiguous` | At least two definite matches in the observation |
| `indeterminate` | Incomplete/error dump, unknown properties, merged Compose semantics, or uncertain window selection |
| `unsupported` | A factory/locator cannot be reproduced by this evaluator |

Only `unique` returns MCP `isError: false`. The tool is a diagnostic, not an existence assertion:
for an expected absence, use `ultron_assert` with `exists: false`. `observedCount` is a lower bound
when `exact` is false. Multiple selected View windows produce `indeterminate` because Espresso's
default root selection cannot be inferred from a dump; narrow `matcher.scope` to one window.

Supported View checks: `LlmViewMatchers.propertyEquals`, selected `withProperty` string/boolean
matchers (`equalTo`, `is`, `not`, `containsString`, `startsWith`, `endsWith`, `allOf`, `anyOf`),
View `withText` / `withContentDescription(string)` / `withClassName`, numeric `withId`,
`withEffectiveVisibility`, `isDisplayed`, `isEnabled`, `isSelected`, `isChecked`, `isFocusable`,
`isFocused`, `isClickable`, and structural `withParent`, `isDescendantOfA`, `hasDescendant`,
`hasSibling`. Combine View matchers with `allOf`, `anyOf`, `not`, `is`. Window scope supports
`LlmRootMatchers.propertyEquals` and selected `withProperty` checks, including logical composition.

Native locators support fully qualified resource IDs, exact `text`, `accessibility id`, string
`-android viewtag`, and supported JSON `-android viewmatcher`. Class-name locators use JVM
`instanceof`, and bare IDs require AUT resource lookup: both are `unsupported` here. Compose
supports `-android viewtag`/`tag name`, exact `text`/`link text` (Text, EditableText and InputText),
and exact `accessibility id` in the unmerged tree. XPath, arbitrary matcher classes, class
inheritance, numeric Hamcrest comparisons and undisclosed runtime properties are not guessed.
Use scalar `propertyEquals` for numeric equality; unsafe JSON integers are not evaluated.
View accessibility checks require the updated server's `contentDescription` getter; missing data
in older dumps produces `indeterminate`.
`withText` also checks `transformed_text`, as Espresso accepts raw or transformed text. Older dumps
without that getter cannot prove a negative raw-text match. `withHint`, `hasFocus` (including focused
descendants), and CharSequence matcher overloads are unsupported because their runtime types or
semantics are absent from this dump. Use `propertyEquals("hint", ...)` for the dump's plain hint text.

For strict checking directly before an operation, add `"preflight": true` to `ultron_act` or
standalone `ultron_element`. It checks a fresh dump, and only `unique` proceeds. Otherwise the
MCP error includes `operation.status: "not_executed"`, the `preflight` report and the dump, with
no action dispatched. Successful actions retain the preflight report next to the operation result.
This is opt-in (default false): blocking on zero current matches would bypass Ultron's normal
waiting for a future element. Assertions retain their waiting/absence semantics.

The UI can change between checking and execution. `runtimeVerificationRequired: true` is always
present; live executor resolution remains authoritative. Dump checks do not reserve a
node or guarantee that a later mutation still targets the same screen.
Lazy-list items that have not been materialized are absent from the observed tree; the checker
does not scroll, expand adapter data or predict elements that may appear later.

To check files locally without any MCP/Android process, save the full snapshot object (the
`structuredContent` or `value` payload, not its HTTP envelope) as `dump.json` and the query object
from an example above as `query.json`, then run:

```sh
node packages/ultron-mcp/bin/check-matcher.mjs --snapshot dump.json --query query.json
```

Exit codes: 0 unique, 1 not_found/ambiguous, 2 indeterminate/unsupported/invalid input.

## Validate

```sh
npm --prefix packages/ultron-mcp test
```

Tests cover schemas, session targeting, failed assertions with evidence, settings, serialization
of concurrent calls, and transport errors without an outer mutation retry. Managed CLI tests use
an executable ADB fixture and HTTP server to verify lazy setup, dynamic-port arguments, once-per-process
installation, device ownership, startup rollback, close/reopen, EOF and signal cleanup without an emulator.

To verify the real Appium MCP CLI, plugin-name registration, stdio discovery and remote WebDriver
requests against a local HTTP fixture, install `appium-mcp@1.99.2` in a separate test directory and run:

```sh
APPIUM_MCP_TEST_ROOT=/absolute/path/to/test-host npm --prefix packages/ultron-mcp run test:host
```

The host smoke test uses a local HTTP fixture and does not access an emulator. The Android
instrumentation tests verify the real driver responses separately.

## Compose operations

`ultron_compose_act` and `ultron_compose_assert` target unique semantics nodes on a `composeOn`
server in both standalone and Appium MCP modes. They do not switch `driver`. The JSON matcher
uses `{name,args?}` (no Espresso `class`/`scope`), with bounded composite/relationship matchers.
The same `useUnmergedTree` selection applies to the operation and its snapshot.

```json
{
  "matcher": {"name": "hasTestTag", "args": ["feed"]},
  "action": "scrollToNode",
  "itemMatcher": {"name": "hasText", "args": ["Order #123"]},
  "timeoutMs": 10000
}
```

```json
{
  "matcher": {"name": "hasTestTag", "args": ["status"]},
  "assertion": "textEquals",
  "expected": ["Saved"],
  "useUnmergedTree": false
}
```

Operation-specific fields go in `options`, for example `{index: 50}`, `{value: 0.5}`,
`{label: "Bookmark"}`, or `{start: 0, end: 5}`. Missing/irrelevant options are rejected. Text
contains assertions follow Compose defaults: set `options.substring: true` for substring matching.
Runtime failures are MCP errors that retain the snapshot, and `ultron_last_result` retains Compose
results. There is no outer transport retry. An absent Lazy node does not prove an absent data item.

See the [complete Compose API and examples](../../docs/guides/compose-operations.md).
