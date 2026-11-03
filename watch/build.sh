#!/usr/bin/env bash
# Build environment for bambu-watch on proteus. Everything lives in the nix profile
# and ~/android-sdk — nothing system-wide, no sudo.
set -euo pipefail
export JAVA_HOME=/var/tmp/proteus/nixprof
export PATH=/var/tmp/proteus/nixprof/bin:$PATH
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
exec gradle --console=plain "$@"
