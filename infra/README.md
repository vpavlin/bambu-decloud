# Infrastructure notes

## Ports

| what | port | notes |
|---|---|---|
| printer MQTT | 8883 (TLS) | self-signed certificate |
| printer FTPS | 990 | implicit TLS |
| printer camera | 6000 | proprietary JPEG stream |
| slicer | 8799 | plain HTTP, **no auth of its own** |

If you address services by name over an overlay network, prefer a **portless** name (a
forwarder on :80): a new port on a new host usually means a firewall rule nobody remembers.

## Firewalls

A local `curl` returning 200 proves nothing about reachability — it never crosses the host
firewall. Test from another machine, over the address family you actually use, by name.

## Licences

Bambu Studio is fetched, never vendored. The error table is extracted from the printer's
own strings; treat it accordingly.
