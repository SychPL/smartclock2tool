# Installing Lenovo Smart Clock 2 Tools

The clock ships with no launcher, no browser, no file manager and **no ADB**, so
the usual "adb install" is not available for the first install. That is what the
TalkBack method below is for: it uses the clock's own hidden browser and needs no
cable, no soldering and no root.

| you are here | go to |
|---|---|
| stock clock, no ADB yet | [Install with TalkBack](#install-with-talkback-no-cable-no-adb) |
| ADB already available | [Install with adb install](#if-you-already-have-adb) |
| APK installed | [First run: one press](#first-run-one-press) |

* The APK to install: `smartclock2tool-debug.apk` from the
  [latest release](https://github.com/SychPL/smartclock2tool/releases/latest).
* Credits for the no-cable install trick: see [Credits](#credits).
* Supported firmware: `LenovoCD-24502F_ROW_1.2.2.627_220105` (check with
  `getprop ro.build.display.id` once you have any shell).

---

## Install with TalkBack (no cable, no ADB)

The trick is to make the clock's screen reader read a URL out loud, then reach the
browser that hides behind TalkBack's own settings screen.

**You need:** the clock set up in the Google Home app up to the clock face, a
phone with the Google Home app, a Google account whose Calendar the clock shows,
and this URL:

```
https://github.com/SychPL/smartclock2tool/releases/latest/download/smartclock2tool-debug.apk
```

### 1. Put that URL on the clock's screen
In Google Calendar create an event in the near future whose **title is only the
URL** - no extra words, no prefix, nothing else. The clock shows upcoming events
as text, and TalkBack reads the whole title.

### 2. Turn on TalkBack
Google Home app -> your clock -> gear icon -> **Accessibility** -> enable
**Screen reader**.

### 3. Make it read the URL
On the clock: ask it to show upcoming events, then swipe sideways until TalkBack
reads the event title (the URL).

### 4. Copy it
Draw an **L** shape on the screen to open the TalkBack menu -> swipe until
**Copy last utterance to clipboard** -> double-tap.

### 5. Open the hidden browser
Draw an **L** again -> swipe until **Open Talkback settings** -> double-tap. Now
scroll to the bottom of that list and tap **Privacy policy**: that opens the
clock's built-in browser. Allow the storage permission prompts (the download
needs them). You can turn the Screen reader off in Google Home at this point.

### 6. Download and install
In the browser: tap the address bar, clear it, long-press -> **Paste**,
long-press the URL -> **Open**. The APK downloads; open it from the Downloads
screen, confirm the install and allow the browser to install unknown apps.

### 7. Get a launcher (recommended)
Repeat steps 1-6 with a launcher, for example
`https://blakadder.com/assets/files/ultra-small-launcher.apk`, and set it as the
default launcher. From then on the clock has a home screen and an app drawer,
Lenovo Smart Clock 2 Tools is just an icon, and installing further APKs is
download + tap.

Then continue with [First run: one press](#first-run-one-press).

### If the download does not start

* the event title must be the URL and nothing else - TalkBack reads the entire
  title, and the browser opens what it read;
* GitHub redirects the download to a CDN. If the clock's old browser refuses,
  host the same file on your own LAN instead: run `python3 tools/filedrop_server.py`
  on your PC (it serves `dist/` on port 8000), put the APK there, and use
  `http://<your-pc-ip>:8000/smartclock2tool-debug.apk` as the event title;
* the clock needs a working Wi-Fi connection (the URL is downloaded, not bundled).

---

## If you already have ADB

Only for the case where ADB is already set up on your clock (USB debugging
enabled, or an already-rooted clock) - otherwise use the TalkBack method above.

```bash
adb install -r smartclock2tool-debug.apk
```

Or build it yourself:

```bash
bash tools/build_native.sh        # optional: rebuild the payloads (needs the NDK)
./gradlew :app:assembleDebug      # result: app/build/outputs/apk/debug/app-debug.apk
```

The app is a **debug build on purpose**: the root channel's allowlist accepts the
app's uid, and that is what lets `adb shell run-as ...` reach root from the PC.

---

## First run: one press

1. Open the app. The left column has the buttons, the right column is the live
   log (monospace, auto-scrolls).
2. Press **`ROOT + ADB`**. The log walks the chain: payloads unpacked -> SELinux
   -> `modprobe_path` armed -> binfmt trigger fired -> channels up -> ADB over
   Wi-Fi on 5555. `STATUS` re-reads the state at any time and prints line by line
   what is actually up (`root:`, `adb:`, `ssh:`).
3. This is also the only step you need after a power cycle - the chain is
   idempotent, pressing it again just re-verifies.

## From the PC

```bash
adb connect <clock-ip>:5555
adb devices
adb shell                  # uid 2000 (shell)
```

Root from the PC, without touching the app UI:

```bash
adb shell run-as pl.mateusz.clockadbprobe \
  /data/user/0/pl.mateusz.clockadbprobe/files/rootkit/clockroot -c id
# uid=0(root) gid=0(root) groups=0(root) context=u:r:kernel:s0
```

`shell` alone already covers a lot: `pm disable-user --user 0 <pkg>` (disabling
the stock assistant, for example), `pm grant`, `settings put`, `input`,
`screencap`, `logcat`.

**SSH (optional).** Before building, put your own public key into
`app/src/main/assets/rootkit/authorized_keys` (a placeholder ships with the repo,
so nothing can log in until you add yours) and rebuild. The app then prints the
exact command:

```bash
ssh -i <your-key> -p 2223 root@<clock-ip>
```

SSH is a convenience only: root and ADB work without it, and `STATUS` says so.

**Turn ADB off when you are done:** press **`ADB WI-FI`** (it is a toggle and
shows the state). While it is ON, `ro.adb.secure=0` and any host on your LAN can
connect as `shell` - see the safety section in the README.

## After a power cycle

Everything this tool does is runtime-only: a power cycle removes root, the
property entry and the listeners, and the clock behaves like stock again. Press
`ROOT + ADB` again (or run `tools/reroot.py` from the PC) and you are back.

---

## Troubleshooting

| symptom | cause / fix |
|---|---|
| `adb connect` refused | ADB over Wi-Fi is OFF, or the chain has not run since the last boot - press `ROOT + ADB`, then check `STATUS` |
| APK downloaded but will not install | allow the browser to install unknown apps when prompted; if there is no prompt, open the file again from Downloads |
| app installed but no icon | you need the launcher from step 7 of the TalkBack method |
| log stops half way, clock reboots | the write primitive hit an unlucky heap layout; power-cycle and press the button again (see the "can panic the kernel" note in the README) |
| `STATUS` says `ssh: 2223 not listening` | the SSH payload did not start (it is optional); root and ADB are unaffected |
| `run-as` says "package not debuggable" | you installed a release build; use the debug APK from the release page |
| ADB works but the clock is on different firmware | the calibration is build-specific - see the README |

---

## Credits

* **[ThomasPrior/LenovoSmartClock2](https://github.com/ThomasPrior/LenovoSmartClock2)**
  - the TalkBack + Calendar trick for installing an APK with no cable, which is
  what makes the main install path above possible.
* `ultra-small-launcher` by blakadder - the minimal launcher used in step 7.
* SimpleSSHD (GPLv3) and Dropbear - the SSH payload; see `NOTICE`.
