#!/usr/bin/env python3
"""Forward an Android debug socket and serve the inspector on this computer."""

import argparse
import http.server
import json
from pathlib import Path
import re
import socket
import subprocess
import sys
import webbrowser

ASSETS = Path(__file__).resolve().parent
FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
}
MAX_REPLY = 8 * 1024 * 1024


class InspectorHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if not self.valid_host():
            return
        if self.path in FILES:
            filename, content_type = FILES[self.path]
            self.reply(200, (ASSETS / filename).read_bytes(), content_type)
        elif self.path == "/api/calls":
            self.device("list")
        elif match := re.fullmatch(r"/api/calls/([0-9]{1,19})", self.path):
            self.device("call " + match[1])
        else:
            self.reply(404, b'{"error":"Not found"}')

    def do_POST(self):
        if not self.valid_host():
            return
        if self.path != "/api/clear":
            self.reply(404, b'{"error":"Not found"}')
            return
        local_origin = f"http://127.0.0.1:{self.server.server_port}"
        if (self.headers.get("Origin") not in (None, local_origin)
                or self.headers.get("X-Network-Inspector") != "1"):
            self.reply(403, b'{"error":"Forbidden"}')
            return
        self.device("clear")

    def valid_host(self):
        expected = f"127.0.0.1:{self.server.server_port}"
        if self.headers.get_all("Host") != [expected]:
            self.reply(403, b'{"error":"Open the dashboard using its printed URL"}')
            return False
        return True

    def device(self, command):
        try:
            with socket.create_connection(("127.0.0.1", self.server.forwarded_port), 3) as peer:
                peer.sendall((command + "\n").encode("ascii"))
                with peer.makefile("rb") as stream:
                    payload = stream.read(MAX_REPLY + 1)
            if len(payload) > MAX_REPLY:
                raise ValueError("Inspector reply exceeds the bridge limit")
            result = json.loads(payload)
            if result is None:
                self.reply(404, b'{"error":"This call has been removed"}')
            else:
                self.reply(200, payload)
        except (OSError, ValueError):
            self.reply(503, b'{"error":"Start or reopen the debuggable app and call NetworkInspector.start(). Verify the selected ADB device and application ID."}')

    def reply(self, status, payload, content_type="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Content-Security-Policy",
                         "default-src 'none'; script-src 'self'; style-src 'self'; "
                         "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("application_id", help="Installed debug package name, including any suffix")
    parser.add_argument("--serial", help="ADB device serial (see adb devices)")
    parser.add_argument("--adb", default="adb", help="Path to adb")
    parser.add_argument("--port", type=int, default=0, help="Dashboard port (default: choose a free port)")
    parser.add_argument("--no-open", action="store_true", help="Print the URL without opening a browser")
    options = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", options.application_id):
        parser.error("Expected an Android application ID")
    if not 0 <= options.port <= 65535:
        parser.error("--port must be between 0 and 65535")

    adb = [options.adb]
    if options.serial:
        adb += ["-s", options.serial]
    forwarded = None
    try:
        with http.server.ThreadingHTTPServer(("127.0.0.1", options.port), InspectorHandler) as server:
            result = subprocess.run(
                adb + ["forward", "tcp:0", "localabstract:" + options.application_id + ".network-inspector"],
                capture_output=True, text=True, check=True, timeout=15,
            )
            forwarded = int(result.stdout.strip())
            server.forwarded_port = forwarded
            url = f"http://127.0.0.1:{server.server_port}/"
            print(f"Network Inspector for {options.application_id}\n{url}\nPress Ctrl+C to stop.", flush=True)
            if not options.no_open:
                webbrowser.open(url)
            server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() or str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Could not start Network Inspector: {error}", file=sys.stderr)
        return 1
    finally:
        if forwarded is not None:
            try:
                subprocess.run(adb + ["forward", "--remove", f"tcp:{forwarded}"],
                               capture_output=True, timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass


if __name__ == "__main__":
    sys.exit(main())
