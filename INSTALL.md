# Installing Lenovo Smart Clock 2 Tools

The app is a normal APK, but the clock is not a normal Android device: out of the
box it has **no launcher, no browser, no file manager and no ADB**, so the first
APK has to get in through a side door. There are two doors; pick one.

Both paths end the same way: **press `ROOT + ADB` in the app** and the clock comes
up with root, ADB over Wi-Fi (5555) and SSH (2223) — no cable, no flashing.

* Credits for the no-cable install trick: see [Credits](#credits) below.

---

## Prerequisites

* the clock on the supported firmware: `LenovoCD-24502F_ROW_1.2.2.627_220105`
  (check with `getprop ro.build.display.id` once you have any shell);
* the clock already set up through the Google Home app up to the clock face;
* a second device with the Google Home app (for the TalkBack trick), or an ADB
  connection (Path B);
* for Path A also: a Google account whose Calendar the clock is showing;
* a way to serve one file over your LAN (Path A) — `tools/filedrop_server.py`
  does exactly that.

---

## Path A — no cable, no soldering (recommended for this tool)

The clock will happily install APKs; it just gives you no UI to do it. The trick
is to make TalkBack *read a URL out loud* and then open the clock's hidden
browser from TalkBack's own settings screen. This is the method from
ThomasPrior's guide, condensed:

1. **Get a URL onto the screen.** In Google Calendar, create an event whose
   *title is only the URL* of the APK you want to install (nothing else in the
   title). The clock shows upcoming events as text.
2. **Turn on TalkBack.** Google Home app → your clock → ⚙ settings →
   Accessibility → enable **Screen reader**.
3. **Serve the APK.** On your PC:

   ```bash
   python3 tools/filedrop_server.py          # serves ./dist on port 8000
   ```

   (If port 8000 is already taken on your PC, edit `PORT` at the top of that
   script, or just use `python3 -m http.server 8000 --directory dist`.)

   and put the APK there (`cp app/build/outputs/apk/debug/app-debug.apk dist/`).
   The URL for the event title is then `http://<your-pc-ip>:8000/app-debug.apk`.
4. **Hear the URL.** Ask the clock to show upcoming events and swipe sideways
   until TalkBack reads the event title (the URL).
5. **Copy it.** Draw an **L** on the screen to open the TalkBack menu → swipe to
   *Copy last utterance to clipboard* → double-tap.
6. **Open TalkBack settings.** Draw an **L** again → swipe to *Open Talkback
   settings* → double-tap. You can turn the Screen reader back off in Google Home
   now.
7. **Reach the browser.** Scroll to the bottom of that settings list and tap
   **Privacy policy** — it opens the clock's built-in browser. Allow the storage
   permission prompts.
8. **Paste and download.** Tap the address bar, clear it, long-press → *Paste*,
   long-press the URL → *Open*. The APK downloads; open it from the Downloads
   screen and confirm the install (allow the browser to install unknown apps).
9. **Repeat** with a launcher, e.g.
   `https://blakadder.com/assets/files/ultra-small-launcher.apk`, and set it as
   the default launcher — from then on you have a home screen and an app drawer,
   and installing further APKs is just *download + tap*.

That is enough to launch **Lenovo Smart Clock 2 Tools** and press `ROOT + ADB`.

---

## Path B — you already have ADB

If ADB is already available to you (USB debugging enabled, or an already-rooted
clock), install over USB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Build the APK first if you have not:

```bash
bash tools/build_native.sh        # optional: rebuild the payloads (needs the NDK)
./gradlew :app:assembleDebug
```

The app is a **debug build on purpose**: the channel's allowlist accepts the
app's uid, and that is what lets `adb shell run-as …` reach root from the PC.

---

## First run — one press

1. Open the app. The left column has the buttons, the right column is the live
   log (monospace, auto-scrolls).
2. Press **`ROOT + ADB`**. The log walks the chain: payloads unpacked → SELinux
   → `modprobe_path` armed → binfmt trigger fired → channels up → ADB over Wi-Fi
   on 5555. `STATUS` re-reads the state at any time and prints, line by line,
   what is actually up (`root:`, `adb:`, `ssh:`).
3. On a fresh boot this is the only step you need. The chain is idempotent —
   pressing it again just re-verifies.

## From the PC

```bash
adb connect <clock-ip>:5555
adb devices
adb shell                  # uid 2000 (shell)
```

Root from the PC, without the app UI:

```bash
adb shell run-as pl.mateusz.clockadbprobe \
  /data/user/0/pl.mateusz.clockadbprobe/files/rootkit/clockroot -c id
# uid=0(root) gid=0(root) groups=0(root) context=u:r:kernel:s0
```

`shell` alone already covers a lot: `pm disable-user --user 0 <pkg>` (disable the
stock assistant), `pm grant`, `settings put`, `input`, `screencap`, `logcat`.

**SSH (optional).** Before building, put your own public key into
`app/src/main/assets/rootkit/authorized_keys` (a placeholder ships, so nothing
can log in until you do) and rebuild. The app prints the exact command:

```bash
ssh -i <your-key> -p 2223 root@<clock-ip>
```

SSH is a convenience only — root and ADB work without it, and `STATUS` says so.

**Turn ADB off when you are done:** press **`ADB WI-FI`** (it is a toggle and
shows the state). While it is ON, `ro.adb.secure=0` and any host on your LAN can
connect as `shell` — see the safety section in the README.

## After a power cycle

Everything this tool does is runtime-only: a power cycle removes root, the
property entry and the listeners, and the clock goes back to stock behaviour.
Press `ROOT + ADB` again (or run `tools/reroot.py` from the PC) and you are back.

---

## Troubleshooting

| symptom | cause / fix |
|---|---|
| `adb connect` refused | ADB over Wi-Fi is OFF, or the chain has not run since the last boot — press `ROOT + ADB`, then check `STATUS` |
| app installed but no icon | you need the launcher from step 9 of Path A |
| log stops half way, clock reboots | the write primitive hit an unlucky heap layout; power-cycle and press the button again (see the "can panic the kernel" note) |
| `STATUS` says `ssh: 2223 not listening` | SSH payload did not start (optional); root and ADB are unaffected |
| `run-as` says "package not debuggable" | you installed a release build; use the debug APK |
| ADB works but the clock is on a different firmware | the calibration is build-specific — see the README |

---

## Credits

* **[ThomasPrior/LenovoSmartClock2](https://github.com/ThomasPrior/LenovoSmartClock2)**
  — the TalkBack + Calendar trick for installing an APK with no cable, which is
  what makes Path A above possible.
* `ultra-small-launcher` by blakadder — the minimal launcher used in step 9.
* SimpleSSHD (GPLv3) and Dropbear — the SSH payload; see `NOTICE`.
