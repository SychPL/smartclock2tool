# Bridge API

Another app on the clock can ask this tool for a short list of privileged operations: switch ADB on or off, grant
itself a permission it declares, become the home app, take the microphone back from the factory shell, or install
its own update without an installer dialog.

It never gets root, a shell, or the ability to run a command of its own choosing. The full contract, including the
reasoning behind every rule, is `docs/SPEC-0.12-tools-bridge.md` in the Helios repository; this page is what you
need to call it.

## Calling

One explicit intent, started for a result:

```java
Intent intent = new Intent("pl.mateusz.clockadbprobe.action.BRIDGE");
intent.setClassName("pl.mateusz.clockadbprobe", "pl.mateusz.clockadbprobe.BridgeActivity");
intent.putExtra("api", 1);
intent.putExtra("op", "adb_off");
intent.putExtra("args", "");                       // operation arguments as JSON, may be empty
intent.putExtra("op_id", "0123456789abcdef0123456789abcdef");   // 32 hex characters, yours to choose
startActivityForResult(intent, REQUEST_CODE);
```

Rules that will otherwise get you a refusal:

- **Start it for a result.** Without that the tool cannot see who is calling, and an anonymous request is refused.
- **Do not add activity flags.** `FLAG_ACTIVITY_NEW_TASK`, `SINGLE_TOP`, `CLEAR_TOP`, `MULTIPLE_TASK`,
  `NEW_DOCUMENT`, `CLEAR_TASK` and `FORWARD_RESULT` are all refused: they would move the request to another task or
  hand the answer to somebody else. Flags the system adds by itself are fine.
- **Choose `op_id` yourself and write it down before you call.** It is how you ask later what happened, and your
  process may not live long enough to see the answer.
- The contract covers Android user 0 only.

## The answer

Always `RESULT_OK` with data, or `RESULT_CANCELED` when the user closed a screen:

| extra | meaning |
| --- | --- |
| `status` | `ok`, `failed`, `denied`, `unsupported`, `unsupported_api`, `busy`, `wrong_firmware`, `in_progress`, `unknown` |
| `detail` | one sentence for a human, filtered: no tokens, no private paths |
| `op_id` | the identifier you sent |
| `state` | a JSON snapshot of the device, always present |

`in_progress` and `unknown` are not failures. Long work carries on after the window closes, and a kernel that hangs
answers nothing at all, so those states exist to be asked about rather than guessed.

## Operations

| op | risk | what it does |
| --- | --- | --- |
| `state` | read | the snapshot, and with `{"about":"<op_id>"}` the fate of an earlier request of yours |
| `root_adb_on` | asks every time | runs the root chain if needed, then turns ADB over Wi-Fi on |
| `adb_on` | asks once | turns ADB on with root already up |
| `adb_off` | asks once | turns ADB off and proves the port went quiet |
| `grant_permission` | asks once | grants the caller one permission it declares; `{"permission":"android.permission.RECORD_AUDIO"}` |
| `write_settings` | asks once | lets the caller change system brightness on its own afterwards |
| `set_home` | asks once | makes the caller the home app |
| `mic_release` | asks once | takes the microphone from the factory shells and restarts them |
| `mic_restore` | asks once | puts them back exactly as they were |
| `install_apk` | asks every time | installs an update of the caller, passed as `Intent.data` with a read grant |

## Trust

The first request from an app opens a screen naming the package and its signing certificate. Nothing happens until
the user agrees. A package that later appears with a different certificate loses everything it was allowed, and is
asked about from scratch.

## The snapshot

```json
{
  "root": true,
  "adb_property": "5555",
  "adb_listening": true,
  "ssh": false,
  "chain": "idle",
  "firmware": "LenovoCD-24502F_ROW_1.2.2.627_220105",
  "firmware_supported": true,
  "tool_version": "2.19.0",
  "mic_holders": ["com.google.android.apps.mediashell"],
  "mic_saved_state": "none",
  "caller": { "record_audio": "granted", "write_settings": "allowed", "is_home": false, "declares_home": true }
}
```

Any field can be the string `"unknown"`, which means the measurement did not finish, not that the answer is no.
`mic_holders` is `"unknown"` without root, because the system anonymises that list for ordinary apps and an empty
list would be a lie told exactly when the problem is there. `adb_property` is configuration; `adb_listening` is a
connection attempt, and only the second one is evidence.

## Asking later

```java
Intent ask = intentFor("state", "{\"about\":\"" + myOpId + "\"}", newOpId(), null);
```

The snapshot then carries an `about` block with the stage (`absent`, `accepted`, `awaiting_consent`, `copying`,
`running`, `installing`, `finished`, `interrupted`), the status, and monotonic timestamps with a `boot_id`. Compare
times only within the same `boot_id`; across a restart they mean nothing.

## Firmware

The root chain is calibrated for `LenovoCD-24502F_ROW_1.2.2.627_220105`. On anything else `root_adb_on` answers
`wrong_firmware` without running. Every other operation works on any build that has the chain up.

## One caveat worth knowing

While ADB over Wi-Fi is on, anyone on the network can reach the clock without a password, and everything the bridge
protects with consent screens can be done without the bridge. The consent screen says so; this page repeats it.
