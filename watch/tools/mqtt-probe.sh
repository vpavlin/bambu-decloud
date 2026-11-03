#!/usr/bin/env bash
# Probe the printer's MQTT from this machine (which is on the printer's LAN).
#
#   ./mqtt-probe.sh 192.168.1.50 /path/to/code-0600 YOUR_SERIAL
#
# The access code is read from a 0600 file and is never echoed. The printer's certificate is
# self-signed, so mosquitto is told to accept it for this test — the app pins it instead.
#
# This tests hypothesis 1 directly: a printer's broker is expected to drop a client that
# subscribes to device/+/report. It tries the wildcard first, then the printer's own topic,
# and reports which one survives.
set -uo pipefail

HOST="${1:?usage: mqtt-probe.sh <host> <code-file> <serial>}"
CODEFILE="${2:?missing code file}"
SERIAL="${3:?missing serial}"

[ -f "$CODEFILE" ] || { echo "no such code file: $CODEFILE"; exit 2; }
PERM=$(stat -c %a "$CODEFILE")
[ "$PERM" = "600" ] || echo "  warning: $CODEFILE is mode $PERM, not 600"

CODE=$(tr -d '\r\n' < "$CODEFILE")
[ -n "$CODE" ] || { echo "code file is empty"; exit 2; }
echo "  host=$HOST  serial=$SERIAL  code_read=${#CODE} chars (not printed)"

MSUB=$(command -v mosquitto_sub || echo /var/tmp/proteus/nixprof/bin/mosquitto_sub)
MPUB=$(command -v mosquitto_pub || echo /var/tmp/proteus/nixprof/bin/mosquitto_pub)
[ -x "$MSUB" ] || { echo "mosquitto_sub not found"; exit 2; }

PUSH='{"pushing":{"sequence_id":"0","command":"pushall"}}'

try_topic() {
  local topic="$1" label="$2"
  echo "  --- $label : subscribe $topic"
  local out
  out=$(timeout 20 "$MSUB" -h "$HOST" -p 8883 --insecure -u bblp -P "$CODE" \
        -t "$topic" -C 3 -W 12 -v 2>&1)
  local rc=$?
  # CONNACK failures show up as "Connection error" / "not authorised" on stderr
  case "$out" in
    *"not authorised"*|*"Connection Refused"*|*"not authorized"*)
        echo "      REJECTED at CONNACK (credentials): $(echo "$out" | head -2 | tr '\n' ' ')"
        return 1;;
  esac
  local n
  n=$(printf '%s\n' "$out" | grep -c "^$topic ")
  echo "      messages received: $n   (exit $rc)"
  if [ "$n" -gt 0 ]; then
    printf '%s\n' "$out" | head -1 | cut -c1-180 | sed 's/^/      first: /'
    # does it look like a printer report?
    printf '%s\n' "$out" | grep -q '"print"' && echo "      contains a print report: yes"
    echo "      ==> this topic SURVIVES"
    return 0
  fi
  echo "      no messages: $(printf '%s\n' "$out" | head -2 | tr '\n' ' ' | cut -c1-120)"
  return 1
}

echo
echo "  === which topic does the broker tolerate? ==="
wild_ok=0; own_ok=0
try_topic "device/+/report" "wildcard" && wild_ok=1
echo
# pushall on the printer's own request topic, then listen on its own report topic
timeout 10 "$MPUB" -h "$HOST" -p 8883 --insecure -u bblp -P "$CODE" \
  -t "device/$SERIAL/request" -m "$PUSH" 2>&1 | head -2 | sed 's/^/      pushall: /'
try_topic "device/$SERIAL/report" "own topic" && own_ok=1

echo
echo "  === verdict ==="
[ "$own_ok" = "1" ] && echo "  own topic works  -> use device/<serial>/report (what 0.7+ does)"
[ "$wild_ok" = "1" ] && echo "  wildcard works   -> the wildcard theory is wrong"
[ "$wild_ok" = "0" ] && [ "$own_ok" = "0" ] && echo "  neither worked   -> credentials or routing, not the topic"
exit 0
