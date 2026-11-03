#!/usr/bin/env bash
# Start the mock printer: TLS MQTT broker on 8883 + camera on 6000.
# The app pins the cert on first connect, so if you regenerate mock.crt, clear the pin.
set -euo pipefail
cd "$(dirname "$0")"
export PATH=/var/tmp/proteus/nixprof/bin:$PATH
[ -f mock.crt ] || openssl req -x509 -newkey rsa:2048 -nodes -keyout mock.key -out mock.crt \
  -days 3650 -subj "/C=CN/O=BBL Technologies Co., Ltd/CN=YOUR_SERIAL" \
  -addext "subjectAltName=IP:127.0.0.1,IP:10.0.2.2,DNS:localhost,DNS:printer.lan.proteus.office.mesh"
mosquitto -c mosquitto.conf & echo "$!" > .broker.pid
./respond.sh & echo "$!" > .respond.pid
python3 mockcam.py & echo "$!" > .cam.pid
echo "mock up: mqtt 8883, camera 6000 (pids in .*.pid)"
