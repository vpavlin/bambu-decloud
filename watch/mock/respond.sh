#!/usr/bin/env bash
# Mock printer behaviour: on pushall, send the full report; then drift mc_percent
# and layer_num as partial updates so that merging (rather than replacing) is tested.
set -uo pipefail
cd "$(dirname "$0")"
SERIAL=YOUR_SERIAL
CRT=./mock.crt
PUB=/var/tmp/proteus/nixprof/bin/mosquitto_pub
SUB=/var/tmp/proteus/nixprof/bin/mosquitto_sub

send_full() {
  $PUB -h 127.0.0.1 -p 8883 --cafile "$CRT" -q 1 \
    -t "device/$SERIAL/report" -f a1-report.json
}

# partial updates: only a couple of fields, exactly like the real printer
drift() {
  local pct=$1 layer=$2 rem=$3
  $PUB -h 127.0.0.1 -p 8883 --cafile "$CRT" -q 1 -m \
   "{\"print\":{\"command\":\"push_status\",\"mc_percent\":$pct,\"layer_num\":$layer,\"mc_remaining_time\":$rem,\"nozzle_temper\":220.5}}" \
   -t "device/$SERIAL/report"
}

$SUB -h 127.0.0.1 -p 8883 --cafile "$CRT" -v -t 'device/+/request' | while read -r topic payload; do
  case "$payload" in
    *pushall*) echo "  pushall on $topic -> sending full report"
               send_full
               for i in 1 2 3; do sleep 2; drift $((47+i)) $((63+i)) $((21-i)); done
               # then a state change, still partial
               $PUB -h 127.0.0.1 -p 8883 --cafile "$CRT" -q 1 \
                 -t "device/$SERIAL/report" -m '{"print":{"command":"push_status","gcode_state":"PAUSE"}}'
               ;;
  esac
done
