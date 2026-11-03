# Building Android apps on proteus

Everything is under `$HOME` and inside the existing nix profile. **No sudo was used.**

## What is installed, and where

| thing | location | how |
|---|---|---|
| JDK 21 | `/var/tmp/proteus/nixprof` (`java`, `keytool`) | `nix profile add --profile /var/tmp/proteus/nixprof nixpkgs#jdk21` |
| Gradle 8.14.4 | same profile | `… nixpkgs#gradle` |
| Android SDK | `~/android-sdk` (446 MB + system image) | command-line tools zip from `dl.google.com`, then `sdkmanager` |
| packages | platform-tools, `platforms;android-35`, `build-tools;35.0.0` | `sdkmanager --licenses` (needed 16 `y`) |

`build.sh` in this repo sets `JAVA_HOME`, `PATH`, `ANDROID_HOME` and calls gradle, so a
build is just `./build.sh assembleDebug`. `local.properties` carries `sdk.dir`.

**Watch out:** `/var/tmp/proteus/nixprof` is under `/var/tmp`. It survives reboots of the
process but **not necessarily a reboot of the box**, and it is shared with the
handwriting-ocr work. If it is ever wiped, the two `nix profile add` lines above rebuild it;
the Android SDK in `~/android-sdk` is not affected.

## Emulator — DOES NOT WORK ON THIS HOST YET

`/dev/kvm` exists and is world-writable, but emulator 37.2.12 **segfaults** on this box
about two seconds after guest start (`exit 139`, log stops just after "Emulator is
performing a full startup"). Tried and failed:

| attempt | result |
|---|---|
| `-no-window -gpu swiftshader_indirect` | segfault |
| `-gpu off -feature -Vulkan -feature -GLDirectMem` | segfault |
| `-accel off` (software) | dies at the same point, no message |
| under `xvfb-run` | needed `libxkbfile`, then hit a nix-glibc mismatch (see below) |

**Do not** add `/nix/store/*/lib` to `LD_LIBRARY_PATH` to satisfy its X libraries: that
pulls a nix `glibc` into the loader and the emulator then fails with
`GLIBC_2.44 not found` on the host's own `libm`. The emulator wrapper sets its own paths;
run it as `emulator …`, never `qemu-system-x86_64` directly, or you will chase missing
libraries that are already there.

Next things to try, in order: an older system image (API 30, smaller and less dependent on
gfxstream), then an older emulator build, then a physical device over `adb connect`.
A phone on the LAN is by far the cheapest way to test — which is what this app ended up
needing anyway.

If it ever boots:

```bash
yes | sdkmanager 'system-images;android-35;google_apis;x86_64'
echo no | avdmanager create avd -n test35 -k 'system-images;android-35;google_apis;x86_64' -d pixel_6
emulator -avd test35 -no-window -no-audio -gpu swiftshader_indirect &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

From inside the emulator the **host** is `10.0.2.2` — that is how you reach a mock server
running on proteus. This is also why `printer.lan.proteus.office.mesh` resolves only to
IPv6 here: use the IPv4 form when testing from a Linux box.

## Gotchas hit on the first app

- **AGP 8.7 wants build-tools 34** and downloads it itself even if you installed 35.
- `gradle` from nix reads `JAVA_HOME`; without it the daemon fails with an unhelpful
  "Unsupported class file major version".
- A **self-signed mock cert needs SANs** (`IP:127.0.0.1,IP:10.0.2.2`) or `mosquitto_sub`
  rejects it with `certificate verify failed` — the CN alone is not enough for a hostname
  check. The real printer's cert is CN-only, which is exactly why the app pins instead of
  verifying a hostname.
- `pkill -f mosquitto` **matches your own shell** and kills the command that is running it.
  Use `pkill -x`, or record the PID.
- The emulator's first boot takes a couple of minutes; `adb wait-for-device` returns earlier
  than the launcher is ready.

## Kept from being learned twice

- Paho `org.eclipse.paho.client.mqttv3:1.2.5` is small and works on Android; you must pass
  your own `socketFactory` or TLS is not used at all.
- `androidx.security:security-crypto:1.1.0-alpha06` for `EncryptedSharedPreferences`.
- Compose with Kotlin 2.x needs the `org.jetbrains.kotlin.plugin.compose` plugin, not just
  `buildFeatures { compose = true }`.
