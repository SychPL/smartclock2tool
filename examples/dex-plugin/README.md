# Dex plugins

The app's LAN agent has a route that hot-loads Java code into its own process:

```
/agent/dex?token=…&url=…&entry=…&arg=…
```

An agent is the only thing you have in a stock clock whose UI is a clock face, and
the app targets SDK 27 on purpose - so hidden framework APIs are still reachable
through reflection. `dex` turns that into a fast loop: you compile a small plugin
on the PC, the app downloads it and runs it **inside itself**, and the plugin's
return string comes back as the HTTP response. No APK rebuild, no reinstall (a
reinstall would disable the accessibility service each time).

## What the app does with the URL

From `AgentServer.runDex()`:

1. `GET` the `url` (must answer 200), save it as `filesDir/plugins/p_<ts>.dex`;
2. load it with `DexClassLoader(dex, codeCacheDir/dexopt, null, <app class loader>)`;
3. look for `public static String run(Context, String)` on `entry` (and fall back
   to `public static void main(String[])`);
4. return whatever it returns as the response body.

Because the parent class loader is the app's own, a plugin can reach the app's
classes as well as the framework - `HelloPlugin` only shows the simplest case.

## Build

```bash
./build.sh                 # -> out/dex/classes.dex
./build.sh MyPlugin.java   # your own plugin, same package layout
```

Needs a JDK (`javac`) and an Android SDK with build-tools (`d8`); set
`ANDROID_HOME` if it is not in the usual place.

## Run

```bash
# 1. serve the dex from the PC (the clock must be able to reach this address)
python3 ../../../tools/filedrop_server.py     # serves dist/ on port 8000
cp out/dex/classes.dex ../../../dist/HelloPlugin.dex

# 2. call the route (token is printed in the app's log pane)
curl "http://<clock-ip>:8555/agent/dex?token=<token>&url=http://<pc-ip>:8000/HelloPlugin.dex&entry=pl.mateusz.plugin.HelloPlugin&arg=hello"
```

A working answer looks like:

```
arg=hello
uid=10058
package=pl.mateusz.clockadbprobe
model=LenovoCD-24502F
sdk=29
ro.build.display.id=1.2.2_627_LenovoCD-24502F_ROW_2201050321 release-keys
```

## Practical notes

* The plugin runs as the **app's uid** (10058 on this clock), not as root. For root work inside
  a plugin, call the root channel (see `exploits/clockroot.c`) rather than
  expecting privileges.
* The URL is fetched **by the clock**, so use your PC's LAN address, not
  `localhost`.
* **One dex only**: `DexClassLoader` is given a single file, so a plugin that
  spills into `classes2.dex` will not load. Keep plugins small (that is why the
  route is aimed at one-off framework experiments).
* This route **executes code you supply**, protected only by the agent token and
  your LAN. Do not expose port 8555 beyond your own network.
* Useful things to try: reading hidden `SystemProperties`, querying
  `PackageManager` internals, driving the app's own services (`AutoTapService`,
  `OverlayService`), or calling a vendor framework service you are probing.
