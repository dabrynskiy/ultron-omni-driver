---
title: Recording scenarios with MCP
---

# Recording scenarios with MCP

The standalone and Appium MCP interfaces can automatically record individual Ultron UI actions
and assertions into the [batch JSON format](batch-operations.md). The agent starts recording,
executes the test case normally, checks expected results, and explicitly finishes recording.
It does not need to reconstruct the scenario from conversation history.

## Agent workflow

1. Prepare/select the Android session and the test's initial state.
2. Call `ultron_recording_start` with `{"name":"Checkout"}` (and optional session selector).
3. Execute individual actions/assertions. Recording is automatic after the start call.
4. Call `ultron_recording_finish` with `{"outcome":"passed"}` when the case is complete.
5. Reuse the returned `scenario` with `ultron_batch`, or load the saved `scenario.json` later.

The finish response contains the recording ID, step count, artifact paths, the runnable scenario
on passed export, and `verification: "not_replayed"`. Completion is declared by the agent;
the recorder cannot infer test-case completeness or automatically prove replay from a clean state.
Use explicit assertions for expected results. Screenshots and getter values are observations,
including when a boolean getter returns `false`.

| Tool | Arguments | Behavior |
| --- | --- | --- |
| `ultron_recording_start` | Required `name`; optional `sessionId` | Start one recording, pinned to the selected session |
| `ultron_recording_status` | `{}` | Inspect progress, issues and draft/journal paths without starting Android |
| `ultron_recording_finish` | Required `outcome: "passed" | "failed"` | Export passed recording, or retain a failed draft without a runnable export |
| `ultron_recording_discard` | `{}` | Stop recording and retain artifacts marked discarded; does not undo UI actions |

Only one recording is active per MCP plugin/process. Its tools are serialized with the pinned
driver's calls, so finishing waits for previously submitted operations. Other driver sessions
cannot be used through the plugin while recording. Standalone session create/attach/close is
blocked until the recording is finished or discarded. External host/ADB actions are not observed.

## Recorded steps

Recorded operations are `ultronAct`, `ultronAssert`, `ultronComposeAct`, `ultronComposeAssert`,
`ultronRecycler`, `ultronBack`, `ultronHideKeyboard`, and all `ultronElement` calls.
Element lookup/getters are retained because locator resolution can itself scroll the UI;
returned values are not automatically converted to assertions. Explicit batch calls are flattened into their
executed steps. Recording never executes a command a second time.

The stored parameters are the normalized driver JSON that was actually sent. Named MCP Espresso
assertions become `condition`/`exists`; `sessionId` and MCP preflight are omitted. An element step
stores its locator and resolved backend, never a cached element ID. Replaying does not require
switching the global driver setting to reproduce that backend.
Element calls made with MCP preflight retain `requireUnique: true`, so replay checks locator
cardinality again before acting; the diagnostic preflight snapshot itself is not replayed.

Snapshots, screenshots, matcher checks, last-result reads and settings
are observations, not standalone replay steps. Their tool names/outcomes are journaled, without
snapshot/image contents. Settings need not become scenario steps: snapshot capture is determined
by batch execution, and each recorded element step has an explicit backend. Preconditions and
external data setup must be reproduced separately.

## Files and persistence

Default directory: `.ultron-recordings` under the MCP process's working directory. Override it
with `ULTRON_RECORDINGS_DIR`; standalone CLI also accepts `--recordings-dir /path/scenarios`.
Programmatic plugin users can pass `new UltronMcpPlugin({recordingsDir: "/path/scenarios"})`.

Each recording has a generated UUID directory and these files:

| File | Content |
| --- | --- |
| `events.jsonl` | Command intents, outcomes and tool observations |
| `draft.json` | Updated after each executed operation; includes metadata, events and successful steps |
| `scenario.json` | Created only by a passed finish; exactly `schemaVersion` and `steps` for `ultron_batch` |

Intent is written before sending an action; its result is recorded afterwards. Draft/export files
are replaced atomically. A process crash may leave a pending intent; the last draft and journal
remain available. Standalone orderly disconnect marks an active draft interrupted. There is no
automatic crash resume. Files use mode `0600`, and newly created recording directories use `0700`.

Input text and matcher arguments are stored literally, including any credentials/test data you
enter. The default directory is ignored in this repository. Copy reusable scenarios into the
desired test directory deliberately. This version does not interpolate variables or infer secrets.

## Failed or ambiguous runs

Passed export requires at least one successful step and no failed, unknown, pending or unsaved
recorded operations. The limit is 100 steps, matching the batch API. If the next operation would
exceed it, that operation is not sent. A blocked preflight does not execute an operation and is
only journaled. Validation failures before execution do not become scenario steps.

An assertion/action failure stays in the journal. A lost/invalid response is recorded as unknown
because a mutation may have completed. Neither is silently dropped to manufacture a passing test.
Finish with `outcome: "failed"` to retain the draft, or discard and start again from the intended
initial state. This version has no automatic path cleanup or removal of exploratory attempts.

If intent cannot be saved, the UI operation is not sent. If saving fails after device execution,
the real operation result is retained with `recording.status: "persistence_error"`; further
recorded mutations and passed export are blocked. Do not repeat an action to repair its recording.
Removing a recording never rolls back completed UI actions. Successful recording and successful
replay are distinct: the recorder does not automatically rerun mutations to certify an export.
