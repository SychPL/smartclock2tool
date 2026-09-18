#!/usr/bin/env python3
"""Pull a file from the clock through the agent exec endpoint in chunks.

The agent exec wrapper drains up to 64 KiB of stdout per call, so we move
gzipped 40 KiB slices base64-encoded and reassemble locally. Usage:

    python tools/agent_pull_file.py <remote_path> <local_path> <token>

Chunk size and count can be overridden for resuming:
    python tools/agent_pull_file.py <remote> <local> <token> [start_chunk]
"""
import base64
import os
import re
import sys
import time
import urllib.parse
import urllib.request

AGENT = os.environ.get("CLOCK_AGENT", "http://<clock-ip>:8555/agent/exec")
CHUNK = 40000  # raw bytes per slice -> ~54 KB base64, under the 64 KiB cap


def pull(remote: str, local: str, token: str, start_chunk: int = 0) -> None:
    # size probe
    cmd = f"stat -c %s {remote}"
    out = agent_exec(token, cmd)
    total = int(out.strip().splitlines()[-1])
    n_chunks = (total + CHUNK - 1) // CHUNK
    print(f"remote={remote} bytes={total} chunks={n_chunks} start={start_chunk}")
    with open(local, "ab" if start_chunk else "wb") as f:
        for ci in range(start_chunk, n_chunks):
            off = ci * CHUNK + 1
            cmd = (f"tail -c +{off} {remote} | head -c {CHUNK}"
                   f" | base64")
            b64 = agent_exec(token, cmd)
            data = base64.b64decode(re.sub(rb"\s+", b"", b64))
            if not data:
                raise RuntimeError(f"empty chunk {ci}")
            f.write(data)
            if ci % 10 == 0 or ci == n_chunks - 1:
                print(f"chunk {ci + 1}/{n_chunks}", flush=True)
    print("done")


def agent_exec(token: str, cmd: str, tries: int = 5) -> bytes:
    q = urllib.parse.urlencode({"token": token, "cmd": cmd})
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(f"{AGENT}?{q}", timeout=25) as r:
                body = r.read()
            if b"REFUSED" in body:
                raise RuntimeError(f"blocklist: {cmd[:120]}")
            if b"bad token" in body:
                raise RuntimeError(body[:200])
            m = re.search(rb"--- STDOUT ---\n(.*?)--- STDERR ---",
                          body, re.S)
            if not m:
                raise RuntimeError(f"no stdout block: {body[:200]}")
            return m.group(1)
        except (urllib.error.URLError, TimeoutError, RuntimeError) as exc:
            wait = 10 * (attempt + 1)
            print(f"agent_exec retry {attempt + 1}/{tries} after {exc}; "
                  f"sleep {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError("agent unreachable")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print(__doc__)
        raise SystemExit(2)
    start = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    pull(sys.argv[1], sys.argv[2], sys.argv[3], start)
