# tools/

Host-side helpers. Everything here talks to the app's LAN **agent** (plain HTTP,
token) or to the clock over adb; none of it needs the research tree.

## The agent

The app starts an HTTP agent on port **8555** (falls back to 8556-8558) and
prints its URL and token in the log pane on the first screen. Token in the URL:

| route | what it does |
|---|---|
| `/agent/status?token=…` | liveness + app version + agent port + state |
| `/agent/report?token=…` | the full probe report as text |
| `/agent/exec?token=…&cmd=…` | run a shell command as the app's uid (a small substring blocklist refuses obviously destructive commands - it is not a sandbox) |
| `/agent/probe?token=…&name=…` | trigger a probe: `rootssh`, `adbwifi`, `adbwifion`, `adbwifioff`, `status`, `getall`, `fullreport`, `devinfo`, `scan`, `checkadb`, `tryadb`, `mic`, `speaker`, `play`, `pull`, `apkserver`, `sendreport`, `devsettings`, `settings`, `floaton`, `taparm`, `tapdisarm`, `openaccessibility`, `selfupdate`, `home`, `openmenu`, `execstop` |
| `/agent/screen?token=…` | a JPEG screenshot of the panel |
| `/agent/dex?token=…&url=…&entry=…` | fetch a dex over HTTP and invoke an entry point (used while probing the vendor framework) |

The token is generated per install and stored in the app's SharedPreferences.

## Tools

* **`agent_push_file.py <local> <remote> <token>`** - push a file to the clock
  through `/agent/exec` (gzip + base64 chunks, so it works with no adb at all).
* **`agent_pull_file.py <remote> <local> <token>`** - the reverse direction.
  Both take the clock's address from `CLOCK_AGENT` if you export it, e.g.
  `export CLOCK_AGENT=http://192.168.1.50:8555/agent/exec`.
* **`build_native.sh`** - cross-builds the arm32 payloads (`exploits/*.c`) with
  the Android NDK; needs `NDK_ROOT` (or a default NDK install) and a git
  checkout (the build id is embedded in the binaries).
* **`prop_trie_sim.py <dump> [names…]`** - replays libc's `prop_area` lookup
  (dot-split segments, trie ordered by name length, value length in the top byte
  of `prop_info::serial`) over a dumped property area. This is how an entry can
  be validated without rebooting the clock:
  `prop_trie_sim.py area.bin service.adb.tcp.port`.
* **`filedrop_server.py`** - serves `dist/` over HTTP so the app's `UPDATE APK`
  button can install a new build without adb.
