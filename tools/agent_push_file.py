#!/usr/bin/env python3
"""Push a local file to the clock through the agent exec endpoint.

Usage: python tools/agent_push_file.py <local> <remote> <token>

Chunks the gzipped payload into <=24 KB base64 pieces (below the agent
line limit), appends remotely, then decodes and syncs. Refuses nothing
on its own; the agent blocklist applies (no 'chmod'/'dd' in commands).
"""
import base64
import os
import gzip
import re
import sys
import urllib.parse
import urllib.request

AGENT = os.environ.get("CLOCK_AGENT", "http://<clock-ip>:8555/agent/exec")
# Request-line cap is 8192 bytes (v2.12 server); the URL-escaped payload
# grows ~3x from '%', so keep the raw base64 piece well under that.
CHUNK = 5000


def exec_cmd(token: str, cmd: str) -> bytes:
    q = urllib.parse.urlencode({"token": token, "cmd": cmd})
    with urllib.request.urlopen(f"{AGENT}?{q}", timeout=60) as r:
        body = r.read()
    if b"REFUSED" in body or b"bad token" in body:
        raise RuntimeError(body[:200])
    return body


def main() -> int:
    local, remote, token = sys.argv[1], sys.argv[2], sys.argv[3]
    # hex-encode the gzip payload: pure [0-9a-f] can never collide with the
    # agent's destructive-pattern blocklist the way base64 can ('wipe',
    # 'flash', 'mount'... are all possible base64 substrings).
    data = gzip.compress(open(local, "rb").read())
    payload = data.hex()
    n = (len(payload) + CHUNK - 1) // CHUNK
    exec_cmd(token, f"rm -f {remote}.hex")
    for ci in range(n):
        piece = payload[ci * CHUNK:(ci + 1) * CHUNK]
        mode = ">" if ci == 0 else ">>"
        exec_cmd(token, f"printf %s '{piece}' {mode} {remote}.hex")
        if (ci + 1) % 20 == 0:
            print(f"chunk {ci + 1}/{n}", flush=True)
    out = exec_cmd(token,
                   f"xxd -r -p {remote}.hex > {remote}.gz && "
                   f"gzip -d < {remote}.gz > {remote} && "
                   f"sync && rm {remote}.hex {remote}.gz && wc -c {remote}")
    print(out.decode("utf-8", "replace").strip()[-80:])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())