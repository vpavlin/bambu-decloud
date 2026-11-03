# The mesh: what actually enables all of this

Everything above assumes devices can reach each other by name, from anywhere, with no port
forwarding, no accounts and no cloud. That layer is **shrooms** — a private overlay
network (WireGuard-based, libp2p for peer discovery/transfer) that gives every machine a
stable address and a DNS name, and runs small agents on each of them.

Without it, the slicer is a script on a LAN, the tablet cannot reach the laptop, and the
phone can only print when it is in the same room as the printer.

## What the mesh gives you

- **A stable address per device**, independent of the LAN it happens to be on. An office
  machine keeps its address when the laptop comes home.
- **DNS names** of the form `<service>.<machine>.<site>.mesh`, resolved by the daemon.
- **Named services** so you never quote an IP or a port:
  `mesh.office.services = ["basecampos:8787", "slicer:8799"]` makes the slicer reachable at
  `http://slicer.<site>.mesh` on port 80, portless.
- **Agents** (`shrooms-agent`) on each machine: a session per role, with a task protocol —
  `send`, `reply`, `get`, `update`, `ack` — so work can be handed between machines and
  followed to completion rather than shouted into a chat.

## Why "portless" matters

A new service on a new port is a firewall rule nobody remembers. Bind it to a **name** that
answers on 80 instead, and the mesh forwards it.

Real example from building this: the slicer listened on `:8799`, and `curl` from the
machine itself returned 200 — while every other machine timed out, because the host
firewall's allowlist covered `80, 7387, 7389, 7394, 8590, 8787, 9180` and nothing else.
**A local `curl` 200 proves nothing about reachability; it never crosses the firewall.**
Test from another machine, over the address family you actually use, by name.

## Two operational facts worth knowing

- **Service names are not access control.** A made-up name on a bare port still answers.
  Only the port-80 name-forwarder matches on name.
- **The daemon may not reload the obvious way.** `systemctl reload shrooms` failed with
  `Failed to set up special execution directory in /run: Permission denied` *before* the
  daemon was signalled. `sudo podman kill --signal HUP shrooms` reloaded it correctly, with
  nothing restarted and no connections dropped.

## Testing the pieces without a phone

A VNC display can be verified with no client at all: speak RFB yourself and save a frame.
Two protocol details cost an hour when writing exactly that:

- `FramebufferUpdate` is `type(1) + padding(1) + count(2)`. Forget the padding byte and
  every later rectangle is misaligned — the symptom is an unexplained message type.
- `SetPixelFormat` is `type(1) + padding(3)` followed by a 16-byte `PIXEL_FORMAT`.
- Servers may send an **empty update first**; keep reading instead of re-requesting.

## Security posture

- VNC and the slicer both **trust the mesh** rather than demanding a password per device —
  that is a choice, and the code supports a token (`token_file`) or `-rfbauth` when the
  trade is wrong for the moment.
- **A shared desktop is not a view, it is a shell.** Screen-sharing a machine that runs a
  Python console hands out code execution. Turn auth on when testing ends.
- Secrets live in mode-600 files on the machine that needs them, never in a repo, a task
  result or a log. `~/.bambu-code`, `~/.vn​c/freecad.passwd`, keystores — same rule.
