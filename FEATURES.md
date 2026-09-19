# Features

Everything this project can do, and where it lives in the code. For installation
see [INSTALL.md](INSTALL.md); for the exploit itself see [exploits/](exploits/).

Three things are being provided, and they are independent:

| channel | who uses it | how |
|---|---|---|
| **root channel** | the app itself, adb shell, ssh | unix socket in the app's private dir (plus an abstract address), uid allowlist |
| **SSH as root** | you, from the PC | Dropbear inside the bundled SimpleSSHD library, port 2223, key-based |
| **ADB over Wi-Fi** | you, from the PC | `service.adb.tcp.port`, written straight into the property area, toggle in the UI |

Plus a **LAN control agent** (HTTP on 8555, token) that exposes probes, a shell,
screenshots and dex hot-loading, so the whole thing can be driven from a PC
without touching the 480x800 screen.

---

## 1. The app UI

Two columns: buttons on the left, live log on the right (monospace, auto-scroll).

| button | what it does |
|---|---|
| **ROOT + ADB** | unpacks the payloads and runs the whole chain (below). Idempotent - pressing it again just re-verifies. |
| **STATUS** | read-only check: prints `root:` (uid/context from the channel), `adb:` (ON/OFF, the property value, `ro.adb.secure`, whether 5555 listens), the `adb connect` line, and `ssh:` (whether 2223 listens, marked optional). |
| **ADB WI-FI** | the toggle. Label shows the live state (`ADB WI-FI: ON` / `OFF`); pressing it flips it. |
| **REPORT** | runs every probe module in sequence and opens the report screen. |
| **UPDATE APK** | InstallActivity: downloads an APK over the LAN (plain HTTP) and installs it. Defaults to the release asset. |
| **DEV SETTINGS** | opens the hidden Developer options (useful to look at vendor toggles). |
| **CLEAR LOG** | clears the log pane. |

The version label shows versionName and versionCode of the installed build.

## 2. The root chain (one press)

`RootKit.run()` unpacks `assets/rootkit/` into `filesDir/rootkit` and runs
`bootstrap.sh` as the app's own uid. The script walks seven steps and prints each:

1. **prepare** - set modes, stage the SSH payload, build a fresh binfmt trigger
   file. The trigger magic is **random on every press**: the kernel deduplicates
   in-flight `request_module` calls by module name, so a fixed magic goes dead
   after the first few triggers.
2. **SELinux** - read `selinux_enforcing` and clear it, unless it already reads 0.
3. **modprobe_path** - read it and point it at the bundled helper, unless it is
   already pointing there.
4. **trigger** - execute the trigger file; the kernel asks for a `binfmt-*` module
   and runs the helper **as root in `kernel_t`**.
5. **SSH** - ask the root channel to start Dropbear detached (a child of the
   usermode-helper session dies with that session).
6. **wait** - poll port 2223.
7. **ADB over Wi-Fi** - run the same recipe as the `ADB WI-FI` button, then report
   the state.

Steps 2 and 3 are the two gated writes of the PowerVR primitive; each is retried
because the kmalloc-1024 layout is stochastic per run. The helper restores
`modprobe_path` on its way out, and the script reports both channels.

## 3. Root channel (`clockroot`)

A root daemon that serves a unix socket in the app's private directory
(`0700`, owned by the app) and on an abstract address. Callers are accepted by
**uid**: `SO_PEERCRED` on `AF_UNIX` cannot be spoofed, which is why the allowlist
works without any authentication protocol.

```
clockroot --serve [dir]        run as root: serve connections
clockroot --stop [dir]         ask the daemon to exit
clockroot --list [dir]         show the allowlist
clockroot --grant UID [dir]    allow a uid (root only)
clockroot --revoke UID [dir]   disallow a uid
clockroot --install-su PKG     copy this binary into PKG's files dir as `su`
clockroot --test-as UID        probe the gate as UID
clockroot -c CMD               run CMD as root (or: su [-c] CMD)
```

Practical consequences:

* `adb shell run-as pl.mateusz.clockadbprobe .../clockroot -c id` returns
  `uid=0(root) ... context=u:r:kernel:s0` - the app's uid is on the list, so a
  plain `adb shell` is one hop away from root.
* `clockroot --grant 2000` lets a normal `adb shell` use the channel directly.
* Refused connections are logged (`clockroot.denied`), and the daemon exits only
  via `--stop` (which it handles by signalling its own parent, so the binary is
  never left holding itself).

## 4. SSH as root (optional)

The payload is [SimpleSSHD](https://github.com/galexander/sshd) 27 (unmodified
upstream libraries, GPLv3 - see `NOTICE`) plus this project's launcher, which
calls Dropbear's `main` inside `libsimplesshd-jni.so`. Bring your own key: put it
in `app/src/main/assets/rootkit/authorized_keys` before building (a placeholder
ships, so nothing authenticates until you do).

The app prints `ssh -i <key> -p 2223 root@<clock-ip>` when the chain finishes.
SSH is a convenience: root and ADB do not depend on it, a launcher failure is
non-fatal in the helper, and `STATUS` says outright whether 2223 is up.

## 5. ADB over Wi-Fi

`adbd` reads `service.adb.tcp.port` **only at startup**, and the property service
refuses our `setprop` (`avc: denied { set }` for `shell_prop`). So the recipe
writes the entry straight into the shared property area and restarts `adbd`:

* **ON**: `proparea --add-chain service.adb.tcp.port 5555`, then
  `proparea --set ro.adb.secure 0`, then kill `adbd` (init restarts it) and wait
  for 5555.
* **OFF**: `proparea --set service.adb.tcp.port 0` - `adbd`'s own check is
  `sscanf(...) == 1 && port > 0`, so 0 means "no TCP listener" without having to
  delete the trie node - plus `ro.adb.secure 1`.

The entry has to be byte-exact, which is what `proparea` is for: the name is
stored split at its dots, the trie is ordered by name *length* first, and
`prop_info::serial` carries the value *length in its top byte* (readers copy
`serial >> 24` bytes out of `value`, so a wrong serial reads back as an empty
string).

`ro.adb.secure=0` matters: these clocks have no `/data/misc/adb/adb_keys` (the
directory answers `ENOKEY`) and there is no UI to confirm a key, so with auth on
nothing could connect at all. While the toggle is ON **any host on your LAN can
connect as `shell`**, and `shell` can reach root through `run-as` - keep it OFF
when you are not using it.

## 6. LAN control agent (HTTP)

Started with the app (and after boot by the boot receiver), listening on 8555
with fallbacks 8556-8558. The token is generated on first run, stored in
SharedPreferences, and printed in the log pane on the first screen.

| route | purpose |
|---|---|
| `/agent/status` | liveness, app version, agent port, apk server state, accessibility state, resident service |
| `/agent/report` | the full probe report as text |
| `/agent/exec&cmd=…` | run a shell command as the app's uid |
| `/agent/probe&name=…` | trigger a probe module (see 7) |
| `/agent/screen` | JPEG screenshot of the panel |
| `/agent/dex&url=…&entry=…&arg=…` | download a `.dex` and run it inside the app's process (see [examples/dex-plugin](examples/dex-plugin/README.md)) |

Details worth knowing:

* `/agent/exec` is filtered by a substring blocklist: `reboot`, `fastboot`,
  `dd`, `flash`, `erase`, `wipe`, `factory reset`, `rm -rf`, `chmod`, `chown`,
  `mount`, `umount`, `mkfs`, `vbmeta`, `bootloader`, `recovery`, `format`,
  `system/bin/rm`, `rm /system`, `rm -r /`. It is a guard rail, **not** a sandbox.
* `report`, `exec`, `screen` and `dex` take a "direct lease" so they cannot
  overlap with a running probe (the same gate the UI buttons use).
* The agent is reachable as long as the app process lives: the resident
  foreground service and the boot receiver keep it that way, so root/ADB state
  can be inspected remotely even when no activity is on screen.

## 7. Probe modules (agent-triggered)

These run inside the app and append to the report. Most are also reachable
through the `REPORT` button; all are triggerable remotely.

| name | what it collects |
|---|---|
| `getall` | every module below, in sequence |
| `fullreport` | device info, network, audio hardware, settings, properties, packages, binder, ADB state, safe shell commands |
| `devinfo` | device/build/network/audio hardware facts |
| `scan` | installed packages and components, focusing on exported ones; binder service list; settings; properties |
| `checkadb` | ADB state (properties, `persist.sys.usb.config`, listening sockets) |
| `tryadb` | the legacy `setprop service.adb.tcp.port` attempt - kept for the record; it is refused by the property service, which is why the real path writes the area |
| `mic` | 5 s recording (needs RECORD_AUDIO) |
| `speaker` / `play` | tone / playback of the recording |
| `pull` | copies world-readable system and vendor APKs plus sepolicy text files into app storage |
| `apkserver` | serves the pulled files + `dist/` over HTTP on 8443 (fallbacks 8444/8080/8888/9000) |
| `sendreport` | uploads the report as JSON - **disabled by default** (empty `ENDPOINT`) |
| `devsettings` / `settings` | opens Developer options / Android Settings |
| `floaton` | toggles the floating nav overlay (Back/Home/collapse) |
| `taparm` / `tapdisarm` | arms/disarms the accessibility helper that clicks the installer's confirm button |
| `openaccessibility` | opens the accessibility settings page (to enable the helper) |
| `selfupdate` | installs the APK from `InstallActivity.DEFAULT_URL` |
| `rootssh` | the whole root chain (same code as the button) |
| `adbwifi` / `adbwifion` / `adbwifioff` | toggle / force ADB over Wi-Fi |
| `status` | the same state lines as the STATUS button |
| `home` / `openmenu` / `showapp` | launcher/home, bring the app forward (full-screen intent) |
| `execstop` | cancels a running remote shell command |

## 8. Report and install screens

* **Report** is an in-memory, sectioned log the probes write into
  (conclusions, command results, per-module findings). `ReportActivity` shows it
  and can copy it to the clipboard or save it; `sendreport` optionally POSTs it
  as JSON to an endpoint **you** configure.
* **InstallActivity** downloads an APK over plain HTTP from a URL you paste (by
  default the published release asset), installs it through the package
  installer, and - if the accessibility helper is armed - clicks the confirm
  button for you, which is what makes unattended self-update possible on a device
  with no touchscreen keyboard.

## 9. Services, receivers, permissions

* `AgentKeepAliveService` - foreground service that keeps the agent (and the root
  channel's client side) alive in the background.
* `AutoTapService` - accessibility service: clicks the installer's confirm button
  when armed, and offers global Back/Home to the overlay. Its `CONFIRM_TEXTS`
  list is deliberately multilingual because it matches the real dialog labels.
* `OverlayService` - draggable nav bar for a clock with no buttons.
* `BootReceiver` - restarts the keep-alive service after a reboot.
* Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, RECORD_AUDIO,
  REQUEST_INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE,
  RECEIVE_BOOT_COMPLETED, USE_FULL_SCREEN_INTENT. Everything stays on your LAN.

## 10. Device-side payloads (`app/src/main/assets/rootkit/`)

| file | role |
|---|---|
| `pvr_mmap_oob_probe` | the PowerVR OOB primitive: page reads, the two gated writes, and the calibration mode |
| `clockroot` | the root channel / su |
| `proparea` | property-area reader/writer (`--set`, `--add-chain`) |
| `hell.sh` | the usermode helper the kernel runs as root: starts the channels, restores `modprobe_path` |
| `bootstrap.sh` | the app-side chain (steps 1-7 above) |
| `adbwifi.sh` | the ADB-over-Wi-Fi recipe (`on`, `off`, or no argument to toggle) |
| `trig` | the binfmt trigger file, rewritten with a fresh magic on every press |
| `authorized_keys` | placeholder; put your SSH public key here and rebuild |
| `stage/` | SimpleSSHD libraries + our Dropbear launcher |

## 11. Host tools and examples

`tools/`: `build_native.sh` (NDK cross-build of the payloads),
`prop_trie_sim.py` (replay libc's property-area lookup over a dump to validate an
entry offline), `agent_push_file.py` / `agent_pull_file.py` (move files through
the agent), `filedrop_server.py` (serve `dist/` for self-update). See
[tools/README.md](tools/README.md).

`examples/dex-plugin/`: a worked example of hot-loading Java into the app through
`/agent/dex`, with a build script and the practical limits. See
[its README](examples/dex-plugin/README.md).

## 12. Limits and safety

* **Runtime-only.** Nothing is written to the bootloader, partitions or the vendor
  image, and nothing survives a power cycle: root, the property entry, the
  listeners and the SELinux change all go away. Press the button again after a
  reboot.
* **The write primitive can panic the kernel.** Keep a way to cut power; if a run
  stops making progress, power-cycle and start over.
* **SELinux is set permissive** while the chain is up, and the helper points
  `modprobe_path` at itself (and restores it).
* **ADB over Wi-Fi disables adb authentication** - see section 5.
* The calibration is **build-specific**: verified on
  `LenovoCD-24502F_ROW_1.2.2.627_220105`. On a different firmware the hardcoded
  addresses are wrong and the probe must be re-calibrated (there is a
  self-calibration mode in the probe that hunts the kernel window).
* The app must be a **debug build**: the root channel accepts the app's uid, and
  that is what `run-as` needs.
