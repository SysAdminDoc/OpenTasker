# External Automation Intents

OpenTasker exposes a narrow broadcast receiver so other apps can trigger user-created automation without binding to internal services.

## Security boundary

Receiver: `com.opentasker.core.external.AutomationTargetReceiver`  
Required permission: `com.opentasker.permission.AUTOMATION`

The receiver is exported only behind this custom permission. Callers should send explicit broadcasts to the OpenTasker package and use ordered broadcasts when they need result extras.

`AUTOMATION` is a signature permission. Android grants it only to apps signed with the same key as OpenTasker, so Home Assistant Companion, Tasker, MacroDroid and every other ordinary app get a `Permission Denial` when they send `RUN_TASK` or the other actions below. Android drops the broadcast before OpenTasker sees it, so nothing shows in the Run Log either.

To trigger OpenTasker from another app, send `com.opentasker.action.PUSH_EVENT` instead and give a profile an Event context with event type `push` and the matching topic. That receiver is exported without a permission and checks a per-install token. Copy the token from Setup with `Copy legacy bridge token`, and keep it private, since anyone holding it can fire your push profiles. The ntfy section below lists the extras.

## Home Assistant Companion command vocabulary

The `integration.home_assistant.webhook` action accepts the Home Assistant Companion envelope
directly when its `message` and `data` fields are used:

```json
{
  "message": "command_broadcast_intent",
  "data": {
    "intent_package_name": "com.opentasker.app",
    "intent_action": "com.opentasker.action.PUSH_EVENT",
    "intent_extras": "com.opentasker.extra.PUSH_TOKEN:<the per-install token>,topic:ha,id:<unique event id>"
  }
}
```

The command value and field names follow the [Home Assistant Companion notification command
contract](https://companion.home-assistant.io/docs/notifications/notification-commands/). The
raw `payload` field remains available for arbitrary webhook JSON and is mutually exclusive with
`message`/`data`, and `command_*` values are checked against the documented command vocabulary.

Companion sends the broadcast as itself. It isn't signed with OpenTasker's key, so a
`command_broadcast_intent` aimed at `RUN_TASK` is refused by Android (see Security boundary).
Point it at `PUSH_EVENT` as above and let a profile with an Event context of type `push` and
topic `ha` run the task. Give each command its own `id`, because a repeated topic and id within
30 seconds counts as the same delivery.

## ntfy push and broadcast compatibility

UnifiedPush JSON and the token-authenticated `com.opentasker.action.PUSH_EVENT` receiver accept
ntfy's standard names `id`, `base_url`, `topic`, `title`, `time`, `encoding`, `content_type`,
`tags`, `tags_map`, `priority`, `muted`, `muted_str`, `attachment_name`, `attachment_type`,
`attachment_size`, and `attachment_expires`. The existing `eventId` and namespaced extras remain
accepted as compatibility aliases.

For a no-adapter ntfy notification action, publish an action whose intent is
`com.opentasker.action.PUSH_EVENT` and whose string extras include:

```text
com.opentasker.extra.PUSH_TOKEN=<the per-install token>
topic=<topic>
id=<unique event id>
```

ntfy documents this as the `broadcast` action and supports `extras.<name>=<value>` in its short
form. OpenTasker does not register the unauthenticated `io.heckel.ntfy.MESSAGE_RECEIVED` action:
an arbitrary installed app could otherwise inject a push trigger. The message body counts toward
the size limit but stays out of event metadata and logs, which is the one place this differs from
ntfy's `message` extra. Click and attachment URLs stay out too
because they can contain credentials or remote identifiers. Use `topic`, `id`, `title`, `tags`,
and the other bounded metadata fields for event matching.

## Actions

### Run a task (asynchronous, protocol v2)

Action: `com.opentasker.action.RUN_TASK`

Runs are **asynchronous**. The receiver authenticates and validates the request, hands the run to
the foreground engine service, and replies immediately with an execution ID. It never waits for the
task: Android expects broadcast work to finish in roughly 10 seconds while an OpenTasker task can
wait up to 30 minutes, so a reply that carried the task's terminal result was reporting an outcome
that had not happened yet. The system could kill the receiver mid-run with no run-log entry.

Callers have to declare the protocol version. A request without it gets refused with an error
naming the extra it needs.

The engine has to be running. If it was stopped and OpenTasker is in the background, Android 12
and later won't let a broadcast start it, so the reply is an error starting with `Engine service
could not be started` and the execution is recorded as `FAILED`. Leaving the engine on (its
notification stays in the shade) avoids this.

Extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.PROTOCOL_VERSION` | int | **Required**, must be `2`. |
| `com.opentasker.extra.TASK_ID` | long | Preferred stable local ID. |
| `com.opentasker.extra.TASK_NAME` | string | Case-insensitive fallback lookup. |
| `com.opentasker.var.<Name>` | string-compatible | Optional task variables. Names must match `[A-Za-z][A-Za-z0-9_]{0,63}`. |

Ordered-broadcast result extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.PROTOCOL_VERSION` | int | The version this build speaks. |
| `com.opentasker.extra.ACCEPTED` | boolean | `true` once the run is validated and enqueued. |
| `com.opentasker.extra.EXECUTION_ID` | string | Poll this with `QUERY_EXECUTION`. |
| `com.opentasker.extra.EXECUTION_STATE` | string | `ACCEPTED` on a successful request. |
| `com.opentasker.extra.EXECUTION_TERMINAL` | boolean | Always `false` here. Acceptance is not completion. |
| `com.opentasker.extra.ERROR` | string, present on failure |  |

The run appears in the normal Run Log with its usual redaction, sourced as `External intent`.

### Query an execution

Action: `com.opentasker.action.QUERY_EXECUTION`

Extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.EXECUTION_ID` | string | **Required**, from the `RUN_TASK` reply. |

Ordered-broadcast result extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.EXECUTION_STATE` | string | `ACCEPTED`, `RUNNING`, `SUCCEEDED`, `FAILED`, or `UNKNOWN`. |
| `com.opentasker.extra.EXECUTION_TERMINAL` | boolean | `true` only for `SUCCEEDED`/`FAILED`. |
| `com.opentasker.extra.TASK_SUCCESS` | boolean | Meaningful only once terminal. |
| `com.opentasker.extra.TASK_DURATION_MS` | long |  |
| `com.opentasker.extra.ERROR` | string | Present for `FAILED` and `UNKNOWN`. |

Results are retained for the 64 most recent executions and survive a process restart. An execution
that was in flight when the engine's process died resolves to `FAILED`, so a caller never polls a
non-terminal state forever. An id that was never issued (or has aged out) reports `UNKNOWN`.

### Set profile enabled state

Action: `com.opentasker.action.SET_PROFILE_ENABLED`

Extras:

| Extra | Type | Notes |
|---|---|---|
| `com.opentasker.extra.PROFILE_ID` | long | Preferred stable local ID. |
| `com.opentasker.extra.PROFILE_NAME` | string | Case-insensitive fallback lookup. |
| `com.opentasker.extra.ENABLED` | boolean | Desired enabled state. |

Turning a profile on passes the same checks as turning it on from the profile list. It's refused
with `RESULT_CANCELED` and the reason in `com.opentasker.extra.ERROR` when the profile is an import
still waiting for its review, when its tasks call each other in a loop, when the app's checks block
it (a clash with an active profile of the same priority over a setting, for one), when it changes
system settings before OpenTasker has the Modify system settings permission, or when a saved
profile or task can't be read to check it. Turning a profile off skips those checks. Either way
only the profile's on/off state is written, and a profile deleted before the write is reported as
removed rather than saved again.

### Query status

Action: `com.opentasker.action.QUERY_STATUS`

Optional extras:

| Extra | Type |
|---|---|
| `com.opentasker.extra.PROFILE_ID` | long |
| `com.opentasker.extra.PROFILE_NAME` | string |

Ordered-broadcast result extras include task/profile counts, enabled profile count, and profile-specific enabled/context state when a profile is found.

## adb examples

A plain `adb shell` doesn't hold the signature permission either, so these get `Permission Denial`
from Android. They work from a root shell (`adb root` on an emulator or a userdebug build). On a
regular phone, use the `PUSH_EVENT` example instead. `am broadcast` still prints `Broadcast
completed: result=0` for a refused one. Android 17 doesn't log the denial to logcat either, but
`adb shell dumpsys activity broadcasts history` lists it as skipped with the permission it needed.

```bash
adb shell am broadcast \
  -a com.opentasker.action.RUN_TASK \
  -p com.opentasker.app \
  --ei com.opentasker.extra.PROTOCOL_VERSION 2 \
  --el com.opentasker.extra.TASK_ID 1 \
  --es com.opentasker.var.User "demo"
```

```bash
adb shell am broadcast \
  -a com.opentasker.action.SET_PROFILE_ENABLED \
  -p com.opentasker.app \
  --el com.opentasker.extra.PROFILE_ID 1 \
  --ez com.opentasker.extra.ENABLED true
```

```bash
adb shell am broadcast \
  -a com.opentasker.action.PUSH_EVENT \
  -p com.opentasker.app \
  --es com.opentasker.extra.PUSH_TOKEN "<the per-install token>" \
  --es topic ha \
  --es id "test-1"
```
