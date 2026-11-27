#!/usr/bin/env bash
# CI helper: prints the UDID of a standard iPhone simulator on the newest installed iOS runtime.
set -euo pipefail
xcrun simctl list devices available -j | python3 -c '
import json, re, sys
devs = json.load(sys.stdin)["devices"]
def ver(rt):
    m = re.search(r"iOS-(\d+)-(\d+)", rt)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
best = None
for rt, items in devs.items():
    if "iOS" not in rt:
        continue
    for d in items:
        m = re.fullmatch(r"iPhone (\d+)", d["name"])
        if m:
            key = (ver(rt), int(m.group(1)))
            if best is None or key > best[0]:
                best = (key, d["udid"])
if best is None:
    sys.exit("no iPhone simulator found")
print(best[1])'
