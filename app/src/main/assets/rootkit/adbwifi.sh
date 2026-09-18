#!/system/bin/sh
# ADB over Wi-Fi toggle: `adbwifi.sh on|off`, or with no argument flip whatever
# state it is in now. --keep-auth leaves ro.adb.secure alone.
#
# Run as root (the app calls it through the clockroot channel:
#   clockroot -c "sh /data/user/0/<pkg>/files/rootkit/adbwifi.sh on").
#
# Why it cannot be a plain setprop: the property service refuses
#   avc: denied { set } ... tcontext=shell_prop permissive=0
# so the entry is written straight into the shared property area that every
# process mmaps (/dev/__properties__/<property_context>). proparea there is a
# port of libc's prop_area.cpp -- the name is stored split at its dots, the trie
# is ordered by name LENGTH first, and prop_info::serial carries the value
# length in its TOP BYTE (readers copy SERIAL_VALUE_LEN(serial) bytes out of
# value, so a wrong serial reads back as an empty string).
#
# adbd only reads service.adb.tcp.port at startup, hence the restart either way.
# Turning it OFF writes 0: adbd's own check is `sscanf(prop,"%d",&port)==1 &&
# port > 0`, so 0 means "no TCP listener" without having to delete the trie node
# (and a power cycle empties the whole area anyway).
F=$(dirname "$0")
PA="$F/proparea"
SP="/dev/__properties__/u:object_r:shell_prop:s0"
SE="/dev/__properties__/u:object_r:exported_secure_prop:s0"
PORT=5555

ACTION=""
for a in "$@"; do
    case "$a" in
        on|off) ACTION="$a" ;;
        --keep-auth) KEEPAUTH=1 ;;
    esac
done

if [ ! -f "$PA" ]; then
    echo "adbwifi: missing $PA"
    exit 1
fi

ip=$(getprop dhcp.wlan0.ipaddress)
case "$ip" in
    [0-9]*.[0-9]*.[0-9]*.[0-9]*) ;;
    *) ip="<clock-ip>" ;;
esac

if [ -z "$ACTION" ]; then
    if [ "$(getprop service.adb.tcp.port)" = "$PORT" ]; then
        ACTION=off
    else
        ACTION=on
    fi
fi

restart_adbd() {
    pid=$(ps -A -o PID,NAME 2>/dev/null | grep -w adbd | head -1 | while read p n; do echo $p; done)
    [ -n "$pid" ] && kill "$pid"
}

wait_port() {
    # $1 = 1 want listening, $1 = 0 want it gone
    want="$1"
    i=1
    while [ "$i" -le 20 ]; do
        if netstat -ltn 2>/dev/null | grep -q ":$PORT"; then
            [ "$want" = "1" ] && return 0
        else
            [ "$want" = "0" ] && return 0
        fi
        sleep 2
        i=$((i + 1))
    done
    return 1
}

if [ "$ACTION" = "on" ]; then
    /system/bin/linker "$PA" "$SP" --add-chain service.adb.tcp.port "$PORT" || exit 1
    echo "adbwifi: service.adb.tcp.port=$(getprop service.adb.tcp.port)"
    if [ "$KEEPAUTH" != "1" ]; then
        # adb auth wants the host key in /data/misc/adb/adb_keys, which does not
        # exist on these clocks and cannot be created (fscrypt answers ENOKEY),
        # and there is no UI to confirm a key. ro.adb.secure=0 drops that
        # handshake; tmpfs state, removed by a power cycle.
        /system/bin/linker "$PA" "$SE" --set ro.adb.secure 0 || exit 1
        echo "adbwifi: ro.adb.secure=$(getprop ro.adb.secure)"
    fi
    restart_adbd
    if wait_port 1; then
        echo "adbwifi: ON  ->  adb connect $ip:$PORT"
        exit 0
    fi
    echo "adbwifi: adbd is not listening on $PORT"
    exit 1
fi

# OFF: 0 disables the TCP listener, and auth goes back to the stock requirement.
if ! /system/bin/linker "$PA" "$SP" --set service.adb.tcp.port 0; then
    if [ "$(getprop service.adb.tcp.port)" = "$PORT" ]; then
        echo "adbwifi: could not turn it off"
        exit 1
    fi
    echo "adbwifi: already OFF (no entry in the property area)"
    exit 0
fi
echo "adbwifi: service.adb.tcp.port=$(getprop service.adb.tcp.port)"
/system/bin/linker "$PA" "$SE" --set ro.adb.secure 1
echo "adbwifi: ro.adb.secure=$(getprop ro.adb.secure)"
restart_adbd
if wait_port 0; then
    echo "adbwifi: OFF (5555 zamkniety)"
    exit 0
fi
echo "adbwifi: port $PORT is still listening"
exit 1
