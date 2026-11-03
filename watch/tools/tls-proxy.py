#!/usr/bin/env python3
"""
A logging TLS proxy in front of the printer's MQTT port, to see what a client actually sends
and what the printer replies. TLS is terminated on both sides, so the MQTT bytes are visible.

    ./tls-proxy.py --listen 18883 --target 192.168.1.50:8883 --cert proxy.crt --key proxy.key

Point a client at 127.0.0.1:18883 with hostname verification off (the proxy's cert is
self-signed, like the printer's).
"""
import argparse, socket, ssl, threading, time

START = time.time()


def log(direction, data):
    ts = f"{time.time()-START:7.3f}"
    if not data:
        print(f"[{ts}] {direction} <closed>", flush=True)
        return
    printable = "".join(chr(b) if 32 <= b < 127 else "." for b in data)
    print(f"[{ts}] {direction} {len(data):5d}B  {data[:64].hex()}", flush=True)
    print(f"[{ts}]     {printable[:120]}", flush=True)


def pump(src, dst, direction, stop):
    try:
        while not stop.is_set():
            data = src.recv(4096)
            if not data:
                log(direction, b"")
                break
            log(direction, data)
            dst.sendall(data)
    except Exception as e:
        print(f"  {direction} error: {type(e).__name__}: {e}", flush=True)
    finally:
        stop.set()
        for s in (src, dst):
            try:
                s.close()
            except Exception:
                pass


def handle(client_raw, ctx_server, target_host, target_port):
    try:
        client = ctx_server.wrap_socket(client_raw, server_side=True)
    except Exception as e:
        print(f"  client TLS failed: {type(e).__name__}: {e}", flush=True)
        return
    ctx_client = ssl.create_default_context()
    ctx_client.check_hostname = False
    ctx_client.verify_mode = ssl.CERT_NONE
    try:
        up_raw = socket.create_connection((target_host, target_port), 8)
        upstream = ctx_client.wrap_socket(up_raw, server_hostname=target_host)
    except Exception as e:
        print(f"  upstream failed: {type(e).__name__}: {e}", flush=True)
        client.close()
        return

    stop = threading.Event()
    t1 = threading.Thread(target=pump, args=(client, upstream, "C->P", stop), daemon=True)
    t2 = threading.Thread(target=pump, args=(upstream, client, "P->C", stop), daemon=True)
    t1.start(); t2.start()
    t1.join(); t2.join()
    print("  --- connection finished", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=18883)
    ap.add_argument("--target", default="192.168.1.50:8883")
    ap.add_argument("--cert", required=True)
    ap.add_argument("--key", required=True)
    a = ap.parse_args()
    th, tp = a.target.split(":")
    tp = int(tp)

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(a.cert, a.key)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", a.listen))
    srv.listen(4)
    print(f"  proxy listening on 127.0.0.1:{a.listen} -> {a.target}", flush=True)
    while True:
        raw, addr = srv.accept()
        print(f"\n  === connection from {addr}", flush=True)
        threading.Thread(target=handle, args=(raw, ctx, th, tp), daemon=True).start()


if __name__ == "__main__":
    main()
