# FreeCAD desktop on a laptop: the units

Four user units. Adjust paths and the mesh address; nothing here needs root.

```ini
# ~/.config/systemd/user/freecad-xvnc21.service
[Unit]
Description=Xvfb :21 for FreeCAD over VNC (GLX required)
[Service]
Type=simple
ExecStart=/usr/bin/Xvfb :21 -screen 0 1920x1152x24 +extension GLX -nolisten tcp
Restart=on-failure
[Install]
WantedBy=default.target
```

```ini
# ~/.config/systemd/user/freecad-freecad21.service
[Unit]
Description=FreeCAD on :21, rendering on the GPU through VirtualGL
After=freecad-xvnc21.service
[Service]
Type=simple
EnvironmentFile=%h/.config/freecad-remote.env      # DISPLAY, XAUTHORITY, VGL/mesa paths
ExecStart=%h/bin/freecad-vgl                          # vglrun -d :0 /usr/bin/freecad
Restart=on-failure
[Install]
WantedBy=default.target
```

```ini
# ~/.config/systemd/user/freecad-x11vnc21.service
[Unit]
Description=x11vnc for :21, mesh address only
After=freecad-xvnc21.service
[Service]
Type=simple
ExecStart=/usr/bin/env -u WAYLAND_DISPLAY XDG_SESSION_TYPE=x11 \
  x11vnc -display :21 -rfbport 5921 -listen 127.0.0.1 -rfbportv6 5921 -listenv6 <mesh-address> \
         -rfbauth %h/.vnc/freecad.passwd -xdamage -defer 10 -wait 10 -ncache 0 -nowf -forever -noxrecord
Restart=on-failure
[Install]
WantedBy=default.target
```

`XAUTHORITY` must be re-read at every login (`/run/user/1000/.mutter-Xwaylandauth.*`), so
it belongs in the env file written by a small start script, not hard-coded.

## BasecampOS: making it a tab

The tablet side is one entry in the image's `basecampos.shell.externalApps`:

```nix
{
  id = "freecad";
  appId = "freecad";
  name = "FreeCAD";
  command = "wlvncc -s -a freecad -e zlib,hextile,copyrect -q 8 -c 6 <mesh-address> 5921";
  embedCommand = "wlvncc -s -d -a freecad -e zlib,hextile,copyrect -q 8 -c 6 <mesh-address> 5921";
}
```

Two launchers, two flag sets: `-d` (no decorations) only makes sense inside a tab. Using
the tab flags in the standalone window is what made it segfault in our first build.

## Checking it without a tablet

```sh
# is the server there, and does it speak RFB?
printf 'RFB 003.008\n' | nc -w 2 <mesh-address> 5921 | head -c 12

# a frame, no client:
python3 tools/rfb-grab.py <mesh-address> 5921 out.png
```
