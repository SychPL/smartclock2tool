# Lenovo Smart Clock 2 Tools

Root, **ADB over Wi-Fi** and SSH on the Lenovo Smart Clock 2 - no cable, no
flashing, nothing persisted. Everything here is runtime-only: a power cycle puts
the clock back to stock.

Built and verified on the retail build **`LenovoCD-24502F_ROW_1.2.2.627_220105`**
(MT8167, PowerVR DDK 1.11@5425693, kernel 4.14.141+ armv7, Android 10).

> Research/owner tooling. Use it on hardware you own, and read
> [Safety and things you should know](#safety-and-things-you-should-know) first.

## What it does

* **One press in the app** (`ROOT + ADB`) walks the whole chain: kernel write
  primitive → usermode helper as root → root channel + SSH + ADB over Wi-Fi.
* **ADB over Wi-Fi** on the clock for `adb install`, `adb shell`, `logcat`,
  `push/pull`, `screencap`, `input` - and for root (see below).
* **SSH as root** on port 2223, key-based (bring your own key).
* **`ADB WI-FI` is a toggle**: the button shows `ADB WI-FI: ON` / `OFF` and
  writes `service.adb.tcp.port` accordingly.
* **A LAN control agent** (HTTP, token) so a PC can drive probes, run commands
  and push/pull files. The host tools in `tools/` use it.

Every feature, one by one, with where it lives in the code: **[FEATURES.md](FEATURES.md)**.

## How it works (short version)

1. **The bug.** `_PMRLogicalOffsetToPhysicalOffset()` in the PowerVR DDK has a
   dense fast path (`ui32NumPhysChunks == ui32NumVirtChunks`) that writes
   `puiPhysicalOffset[idx] = uiOffset; bValid = TRUE` without bounds checks.
   The page array is a kmalloc-96 object, so a PMR mapped with enough chunks
   makes the kernel read page pointers out of the *neighbouring* slab objects -
   an OOB read of the physical page table. On this build the mapping is stable
   and calibration free: `pfn = 0x40C78 + (V - 0x10f310e0) / 36`, verified on
   15/15 consecutive kernel pages (no KASLR).
2. **The write.** The same object gives a write whose value is gated by the
   64 bytes at the start of the target page, which is enough to write
   `modprobe_path`.
3. **Root.** `modprobe_path` points at the bundled helper, then an unknown
   binfmt magic is executed; the kernel runs the helper as **root in
   `kernel_t`**. The helper starts the root channel (a unix socket in the app's
   private directory) and Dropbear, then restores `modprobe_path`.
4. **ADB over Wi-Fi.** `adbd` reads `service.adb.tcp.port` only at startup, and
   the property service refuses our `setprop` (`avc: denied { set }` on
   `shell_prop`), so the entry is written straight into the shared property area
   (`/dev/__properties__/u:object_r:shell_prop:s0`). It has to be byte-exact:
   the name is stored split at its dots, the trie is ordered by name **length**
   first, and `prop_info::serial` carries the value **length in its top byte**
   (readers copy `serial >> 24` bytes out of `value`, so a wrong serial reads
   back as an empty string).
5. **SELinux** is set permissive by the chain (`selinux_enforcing` is one of the
   two targets of the write).

The native sources in `exploits/` carry the details, including the traps that
were hit while developing this (the trigger dedup below, the serial encoding
above).

## Requirements

* the clock on the build above, reachable over the LAN;
* host: **JDK 21**, **Android SDK 34** (`ANDROID_HOME`), Python 3 for the tools;
  the **NDK** (r27 tested) only if you rebuild the native payloads;
* the app must be a **debug** build - the root channel is reached from the PC
  through `run-as`.

## Build

```bash
bash tools/build_native.sh          # optional: rebuild payloads (needs the NDK)
./gradlew :app:assembleDebug        # APK
```

`app/src/main/assets/rootkit/` already contains prebuilt payloads, so the gradle
build alone produces a working APK.

## Install and use

On a stock clock there is **no ADB yet**, so the first install goes through the
clock's own hidden browser: **[INSTALL.md](INSTALL.md)** walks through it step by
step (TalkBack reads an APK URL out loud, the browser downloads it). The APK
itself is on the [latest release](https://github.com/SychPL/smartclock2tool/releases/latest).

If ADB is already available to you, it is just:

```bash
adb install -r smartclock2tool-debug.apk
```

Full step-by-step, including how to get the **first APK onto a stock clock with
no cable** (the TalkBack + Calendar trick): **[INSTALL.md](INSTALL.md)**.

Then, in the app: press **`ROOT + ADB`** and watch the right-hand log pane
(`STATUS` re-reads the state any time). From the PC:

```bash
adb connect <clock-ip>:5555
adb shell
```

Root from the PC, with no UI involved:

```bash
adb shell run-as pl.mateusz.clockadbprobe \
  /data/user/0/pl.mateusz.clockadbprobe/files/rootkit/clockroot -c id
# uid=0(root) gid=0(root) groups=0(root) context=u:r:kernel:s0
```

`shell` (uid 2000) can do a lot without root - `pm disable-user`, `pm grant`,
`settings put` all work - and `run-as` above turns it into root, because the
channel's allowlist accepts the app's uid.

**SSH:** put your public key(s) into
`app/src/main/assets/rootkit/authorized_keys` *before* building (a placeholder
ships with the repo, so nothing authenticates until you add yours) and rebuild.
The app prints the exact `ssh -i <key> -p 2223 root@<clock-ip>` command when the
chain finishes.

The device-side recipes are plain shell and can be run by hand through the root
channel:

```bash
F=/data/user/0/pl.mateusz.clockadbprobe/files
$F/rootkit/clockroot -c "sh $F/rootkit/adbwifi.sh on"     # or: off
$F/rootkit/clockroot -c "sh $F/rootkit/bootstrap.sh"      # the whole chain
```

## Letting another app use this

Another app on the clock can ask this tool for a short list of privileged operations rather than for root: switch
ADB, grant itself a permission it declares, become the home app, take the microphone back from the factory shell,
install its own update. It gets no root, no shell and no way to run a command of its own choosing.

Every app is named and asked about before anything happens, and an app that reappears with a different signing
certificate starts from zero. The interface is **[docs/BRIDGE-API.md](docs/BRIDGE-API.md)**.

## Host tools (`tools/`)

| tool | purpose |
|---|---|
| `build_native.sh` | cross-builds the arm32 payloads with the NDK |
| `prop_trie_sim.py` | replays libc's `prop_area` lookup over a dumped property area, to validate an entry offline |
| `agent_push_file.py` / `agent_pull_file.py` | move files through the app's HTTP agent (works without adb) |
| `filedrop_server.py` | serves `dist/` so the app's `UPDATE APK` button can self-update |

See `tools/README.md` for the agent endpoints and the token (the app prints it in
its log).

`examples/dex-plugin/` is a worked example of the agent's `/agent/dex` route: a
small Java plugin, a `build.sh` (javac + d8) and a README that explains how the
app loads it and what its limits are.

## Safety and things you should know

* **Everything is runtime-only.** A power cycle removes root, the property entry,
  the listeners and the SELinux change.
* The write primitive can **panic the kernel**. Keep a way to cut power. If a run
  stops making progress, power-cycle and start again.
* **ADB over Wi-Fi disables adb authentication** (`ro.adb.secure=0`) because
  these clocks have no `/data/misc/adb/adb_keys` (the directory answers `ENOKEY`)
  and no UI to confirm a key. While the toggle is **ON**, *any host on your LAN*
  can connect as `shell` - and `shell` can reach the root channel through
  `run-as`. Keep it **OFF** when you are not using it.
* While the chain is up, SELinux is **permissive** and `modprobe_path` is
  pointed at the bundled helper (the helper restores it).
* Nothing here touches the bootloader, partitions or the vendor image, and
  nothing survives a reboot.

## Scope of this repository

Source and tooling only, on purpose. The author's research data is **not**
included: device dumps, fuzzing campaigns, the findings journal, pulled system
files and the vendor's open-source tarball. There is no telemetry either - the
report uploader ships with an empty `ENDPOINT`, so it refuses to send anything
until you point it at a collector of your own.

## Third-party components

See `NOTICE`. The APK bundles SimpleSSHD (GPLv3) and Dropbear; both keep their
own licenses.

## License

**MIT** - see `LICENSE`.

The bundled third-party components keep their own terms: SimpleSSHD is GPLv3
(and its library embeds Dropbear), so an APK you redistribute that contains those
binaries is bound by GPLv3 for that combined work; the MIT terms cover the rest
of the sources. Details and upstream links are in `NOTICE`.
