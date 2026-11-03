#!/usr/bin/env bash
cd "$(dirname "$0")"
for f in .broker.pid .respond.pid .cam.pid; do
  [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"
done
pkill -x mosquitto 2>/dev/null || true
echo "mock stopped"
