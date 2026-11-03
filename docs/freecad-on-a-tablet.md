# FreeCAD on a tablet, without the cloud

A desktop CAD program, driven from a pen tablet, with the geometry running on a laptop
that has the GPU. Nothing is installed on a vendor server and nothing leaves your network.

This is the part that took the longest to get right and the part that is easiest to get
subtly wrong. Read the whole page before changing a value.

```
tablet (BasecampOS, sway)                  laptop (X11 desktop, no monitor)
┌──────────────────────────┐   RFB over  ┌─────────────────────────────────────┐
│ Basecamp tab             │   the mesh  │ Xvfb :21  1920x1152x24  +GLX        │
│  └ QtWayland compositor  │◄───────────►│  └ openbox                          │
│     └ wlvncc (patched)   │   x11vnc    │     └ FreeCAD  ⇢ vglrun -d :0       │
└──────────────────────────┘             │                         ⇢ GPU       │
                                         └─────────────────────────────────────┘
```

## Why it is built this way

FreeCAD needs a desktop, a 3-button mouse and an OpenGL context. A tablet has none of
those in the way FreeCAD expects. So the laptop runs the program and the tablet is a
screen — but the *geometry* must run on the GPU, or you spend the session watching a
software rasteriser.

Two things make that hard on a headless X server:

1. **TurboVNC's `Xvnc` has no GLX extension.** Qt asks the *server* whether GLX exists, so
   FreeCAD reports `QXcbIntegration: Cannot create platform OpenGL context, neither GLX
   nor EGL are enabled` and the 3D viewport stays a grey rectangle. `+iglx` cannot add a
   compiled-out extension.
2. **`glxinfo` under VirtualGL is not evidence.** VirtualGL supplies GLX to the *client*,
   so `glxinfo` happily names the GPU while Qt still fails against the server's extension
   list. Two different questions; only one of them is answered by that command.

`Xvfb` does ship GLX (via Mesa's software rasteriser), and that is enough for Qt to build
a context. VirtualGL then intercepts the app's GLX calls and renders on the real GPU.

## The pieces

### 1. A virtual X server that has GLX

```
Xvfb :21 -screen 0 1920x1152x24 +extension GLX -nolisten tcp
DISPLAY=:21 openbox &
```

`1920x1152` is not arbitrary: it is the aspect ratio of the app buffer a Basecamp tab
gives you on a 1920x1200 panel at sway scale 1.2 (see *Scaling* below), so the viewer fits
it with one uniform scale and no letterboxing. It is also only 2.2 Mpx to encode, which
matters because the encoder is on the CPU.

### 2. VirtualGL against the real GPU

```
vglrun -d :0 /usr/bin/freecad
```

`-d :0` is XWayland — the session's own X server, which *does* have hardware GLX. VirtualGL
renders there and blits into `:21`.

Do **not** use `-d egl` with a distribution (non-nix) binary: VirtualGL's EGL back end
fails inside the host process with `[VGL] ERROR: in buildCfgAttribTable-- 461: Could not
enable OpenGL API`. Its GLX back end works. With a nix-provided VirtualGL you also need
nix's Mesa named explicitly, because nix's glvnd looks for drivers in `/run/opengl-driver`
which does not exist off NixOS:

```
M=/nix/store/...-mesa
export __EGL_VENDOR_LIBRARY_FILENAMES=$M/share/glvnd/egl_vendor.d/50_mesa.json
export LIBGL_DRIVERS_PATH=$M/lib/dri
export GBM_BACKENDS_PATH=$M/lib/gbm
export __GLX_VENDOR_LIBRARY_NAME=mesa
export LD_LIBRARY_PATH=$M/lib
```

The GPU path depends on the user's Wayland session being active, because it goes through
XWayland's auth cookie, which changes at every login:

```
XAUTHORITY=$(ls -t /run/user/1000/.mutter-Xwaylandauth.* | head -1)
```

Read it fresh at start. If the session is gone, FreeCAD still runs — on llvmpipe.

### 3. Serving it over RFB, on the mesh only

```
env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
x11vnc -display :21 -rfbport 5921 -listen 127.0.0.1 -rfbportv6 5921 -listenv6 <mesh-addr> \
       -rfbauth ~/.vnc/freecad.passwd \
       -xdamage -defer 10 -wait 10 -ncache 0 -nowf -forever -noxrecord
```

Four traps, all hit for real:

- **x11vnc quits under a Wayland session** unless `WAYLAND_DISPLAY` is unset and
  `XDG_SESSION_TYPE=x11` is set.
- **`-listen6` alone also opens `[::]:5900`** on every IPv6 address. The safe form is the
  pair above: `-listen 127.0.0.1` plus `-rfbportv6 … -listenv6 <mesh address>`.
- **`-xdamage` is the CPU saver**: VirtualGL draws into `:21` through X, so damage hints
  arrive and only changed regions are encoded.
- **`-defer 10 -wait 10`** (half the defaults) shortens the latency of a drag-rotate, which
  is the interaction that decides whether this feels alive.

**A shared desktop is not a view, it is a shell.** FreeCAD has a Python console and runs as
you. With VNC auth off, anything on the mesh can run code as you. Auth off is for testing
only; then restore `-rfbauth` and have the client read the password from a mode-600 file.

### 4. The tablet side: BasecampOS

The `slicer`/VNC pair is consumed by a **Basecamp app tab** — an embedded Qt Wayland
compositor that hosts a native Wayland client. `wlvncc` is that client, and stock
`wlvncc` does not survive the tab:

- it binds `wl_seat` v5 while Qt 6.9's compositor offers v4 → fatal protocol error
- `wl_pointer` v4 sends no `frame` events, and wlvncc only acted on frames → pointer input
  would be dead; every event must be treated as a frame
- it drew into buffers the compositor had not released (thousands of "back-buffer is still
  attached"); it must draw only into released buffers and catch up on release
- with no cursor theme it crashes: `wl_cursor_theme_load` can return a theme that contains
  no `left_ptr`, and the `assert` is compiled out in release builds, so
  `cursor->images[0]` dereferences NULL on the first pointer enter. Fixed by falling back
  to no local cursor, plus exporting `XCURSOR_PATH` / `XCURSOR_THEME` / `XCURSOR_SIZE`.

Launcher:

```
wlvncc -s -d -a freecad -e zlib,hextile,copyrect -q 8 -c 6 <mesh address> 5921
```

`-s` because that compositor offers no EGL; `-d` because the tab is the frame. Note `-n`
means **hide cursor and takes no argument** — `-n FreeCAD` silently makes "FreeCAD" the
server address.

### Scaling: why nothing in a tab is 1:1 today

The tablet is 1920x1200 at sway scale 1.2 → 1600x1000 logical. But Basecamp renders at
**pixel ratio 2**, and sway then downsamples its buffer by 0.6. An embedded tab item is
about 1599x959 logical, so its app buffer is about 3198x1918 — larger than the panel.

Consequences: size the remote desktop to the *buffer's* aspect (hence 1920x1152), and
expect one resample. True 1:1 needs Basecamp to run at the fractional scale, which is an
open question on that platform.

## Input: the middle button

FreeCAD rotates with the middle button. On the touchpad, a three-finger tap maps to
`BTN_MIDDLE` and reaches a client inside a tab — verified with a real Brave tab opening a
link in a background tab, and with `wev` on the tab's own compositor socket.

But the better answer for a pen tablet is FreeCAD's **Gesture** navigation: one-button drag
rotates, two-finger drag pans, pinch zooms. Set it in *Preferences → Display → 3D
Navigation* (or from the status-bar navigation menu). A three-finger **tap** is a click —
it can never hold a drag, so tap-and-drag orbiting was never going to work.

## Dead ends, recorded so nobody repeats them

| attempt | why it failed |
|---|---|
| TurboVNC `Xvnc` + `+iglx` | no GLX extension at all; nothing to enable |
| `vglrun -d egl` with a host binary | `buildCfgAttribTable-- 461: Could not enable OpenGL API` |
| `glxinfo` as proof | VirtualGL answers for the client; Qt asks the server |
| headless `sway` + `wayvnc` | `wayvnc` has **no password auth** in any flag — GPU yes, auth no |
| 2560x1600 remote display | correct pixels, 4.1 Mpx through a CPU encoder |
| synthetic mouse without `REL_WHEEL` | libinput treats a wheel-less pointer as middle-button scroll, so middle-drag became scrolling and looked like a compositor bug |
