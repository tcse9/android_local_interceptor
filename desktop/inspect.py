#!/usr/bin/env python3
"""Forward an Android debug socket and serve the inspector on this computer."""

import argparse
import base64
import http.client
import time
import threading
from urllib.parse import parse_qs, urlsplit
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
    "/formats.js": ("formats.js", "text/javascript; charset=utf-8"),
    "/workbench.js": ("workbench.js", "text/javascript; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/style.css": ("style.css", "text/css; charset=utf-8"),
}
MAX_REPLY = 8 * 1024 * 1024


APPLICATION_ID = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")
MAX_INPUT = 2 * 1024 * 1024


class Devices:
    def __init__(self, adb, application_id, serial=None):
        self.adb = adb
        self.application_id = application_id
        self.serial = serial
        self.forwards = {}
        self.lock = threading.Lock()

    def run(self, *arguments):
        return subprocess.run([self.adb, *arguments], capture_output=True,
                              text=True, check=True, timeout=12).stdout.strip()

    def inventory(self):
        devices = []
        for line in self.run("devices", "-l").splitlines()[1:]:
            fields = line.split()
            if len(fields) < 2:
                continue
            attributes = dict(field.split(":", 1) for field in fields[2:] if ":" in field)
            devices.append({"serial": fields[0], "state": fields[1],
                            "name": attributes.get("model", fields[0]).replace("_", " ")})
        return {"devices": devices, "applicationId": self.application_id, "serial": self.serial}

    def target(self, query):
        serial = query.get("serial", [self.serial])[0]
        application_id = query.get("app", [self.application_id])[0]
        if not APPLICATION_ID.fullmatch(application_id):
            raise ValueError("Invalid application ID")
        ready = [item["serial"] for item in self.inventory()["devices"] if item["state"] == "device"]
        if not serial and len(ready) == 1:
            serial = ready[0]
        if not serial or serial not in ready:
            raise ValueError("Select an online device. Authorize USB debugging on the device if needed.")
        return serial, application_id

    def port(self, target):
        with self.lock:
            if target not in self.forwards:
                serial, application_id = target
                self.forwards[target] = int(self.run("-s", serial, "forward", "tcp:0",
                    "localabstract:" + application_id + ".network-inspector"))
            return self.forwards[target]

    def command(self, query, command):
        target = self.target(query)
        try:
            with socket.create_connection(("127.0.0.1", self.port(target)), 3) as peer:
                peer.settimeout(8)
                peer.sendall((command + "\n").encode("utf-8"))
                with peer.makefile("rb") as stream:
                    payload = stream.read(MAX_REPLY + 1)
            if len(payload) > MAX_REPLY:
                raise ValueError("Inspector reply exceeds the bridge limit")
            return json.loads(payload)
        except (OSError, json.JSONDecodeError) as error:
            with self.lock:
                port = self.forwards.pop(target, None)
                if port:
                    self.run("-s", target[0], "forward", "--remove", f"tcp:{port}")
            raise ValueError("Start the selected debug app and call NetworkInspector.start(). "
                             "Reconnect or refresh to retry.") from error

    def close(self):
        with self.lock:
            for (serial, _), port in self.forwards.items():
                try:
                    self.run("-s", serial, "forward", "--remove", f"tcp:{port}")
                except (OSError, subprocess.SubprocessError):
                    pass
            self.forwards.clear()


def replay_request(data):
    method = data.get("method", "")
    url = data.get("url", "")
    if not isinstance(method, str) or not re.fullmatch(r"[A-Z]+", method):
        raise ValueError("Use an uppercase HTTP method")
    parsed = urlsplit(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Replay requires an HTTP or HTTPS URL without embedded credentials")
    headers = data.get("headers", [])
    if not isinstance(headers, list) or len(headers) > 200:
        raise ValueError("Invalid request headers")
    body = base64.b64decode(data.get("bodyBase64", ""), validate=True)
    if len(body) > 1024 * 1024:
        raise ValueError("Replay request body exceeds 1 MiB")
    if method in ("GET", "HEAD") and body:
        raise ValueError("Remove the body for GET or HEAD requests")
    connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    started = time.monotonic()
    connection = connection_type(parsed.hostname, parsed.port, timeout=25)
    try:
        connection.putrequest(method, (parsed.path or "/") + ("?" + parsed.query if parsed.query else ""),
                              skip_accept_encoding=True)
        for pair in headers:
            if not isinstance(pair, list) or len(pair) != 2 or not all(isinstance(x, str) for x in pair):
                raise ValueError("Each header must contain a name and value")
            name, value = pair
            if not re.fullmatch(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+", name) or "\r" in value or "\n" in value:
                raise ValueError("Invalid header characters")
            if name.lower() not in ("host", "content-length", "transfer-encoding", "connection",
                                    "accept-encoding", "proxy-connection", "upgrade", "expect"):
                connection.putheader(name, value)
        if body or method not in ("GET", "HEAD"):
            connection.putheader("Content-Length", str(len(body)))
        connection.endheaders(body or None)
        response = connection.getresponse()
        headers_ms = round((time.monotonic() - started) * 1000)
        payload = response.read(256 * 1024 + 1)
        truncated = len(payload) > 256 * 1024
        payload = payload[:256 * 1024]
        content_type = response.getheader("Content-Type", "")
        capture = {"state": "partial" if truncated else "complete", "bytes": len(payload),
                   "capturedBytes": len(payload), "truncated": truncated, "mediaType": content_type,
                   "base64": base64.b64encode(payload).decode("ascii")}
        if re.search(r"text/|json|xml|javascript|x-www-form-urlencoded", content_type, re.I):
            charset = re.search(r"charset=([^;\s]+)", content_type)
            try:
                capture["text"] = payload.decode(charset[1].strip('"') if charset else "utf-8", errors="replace")
            except LookupError:
                capture["text"] = payload.decode("utf-8", errors="replace")
        return {"status": response.status, "responseHeaders": [[k, v, False] for k, v in response.getheaders()],
                "responseBody": capture, "headersMs": headers_ms,
                "elapsedMs": round((time.monotonic() - started) * 1000),
                "phase": "Finished", "protocol": "HTTP/1.1", "responseUrl": url, "source": "Desktop replay"}

    finally:
        connection.close()


class InspectorHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if not self.valid_host():
            return
        parsed = urlsplit(self.path)
        query = parse_qs(parsed.query)
        try:
            if parsed.path in FILES:
                filename, content_type = FILES[parsed.path]
                self.reply(200, (ASSETS / filename).read_bytes(), content_type)
            elif parsed.path == "/api/devices":
                self.json_reply(self.server.devices.inventory())
            elif parsed.path == "/api/calls":
                self.json_reply(self.server.devices.command(query, "list"))
            elif match := re.fullmatch(r"/api/calls/([0-9]{1,19})", parsed.path):
                self.json_reply(self.server.devices.command(query, "call " + match[1]))
            elif parsed.path == "/api/rules":
                self.json_reply(self.server.devices.command(query, "rules"))
            else:
                self.reply(404, b'{"error":"Not found"}')
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            self.reply(503, json.dumps({"error": str(error)}).encode())

    def do_POST(self):
        if not self.valid_host():
            return
        local_origin = f"http://127.0.0.1:{self.server.server_port}"
        if (self.headers.get("Origin") not in (None, local_origin)
                or self.headers.get("X-Network-Inspector") != "1"):
            self.reply(403, b'{"error":"Forbidden"}')
            return
        parsed = urlsplit(self.path)
        query = parse_qs(parsed.query)
        try:
            if parsed.path == "/api/clear":
                self.json_reply(self.server.devices.command(query, "clear"))
            elif parsed.path in ("/api/replay", "/api/rules"):
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > MAX_INPUT:
                    raise ValueError("JSON payload must be between 1 byte and 2 MiB")
                self.connection.settimeout(5)
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError("Expected a JSON object")
                if parsed.path == "/api/replay":
                    self.json_reply(replay_request(data))
                else:
                    command = json.dumps({"rules": data.get("rules", [])}, separators=(",", ":"))
                    if len(command.encode()) > 256 * 1024:
                        raise ValueError("Rules exceed 256 KiB")
                    self.json_reply(self.server.devices.command(query, command))
            else:
                self.reply(404, b'{"error":"Not found"}')
        except (OSError, ValueError, TypeError, AttributeError, http.client.HTTPException,
                subprocess.SubprocessError) as error:
            self.reply(400, json.dumps({"error": str(error)}).encode())

    def json_reply(self, data):
        if isinstance(data, dict) and "error" in data:
            self.reply(400, json.dumps(data).encode())
        elif data is None:
            self.reply(404, b'{"error":"Call removed or feature unavailable in this Android library version"}')
        else:
            self.reply(200, json.dumps(data).encode())

    def valid_host(self):
        expected = f"127.0.0.1:{self.server.server_port}"
        if self.headers.get_all("Host") != [expected]:
            self.reply(403, b'{"error":"Open the dashboard using its printed URL"}')
            return False
        return True

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

    devices = Devices(options.adb, options.application_id, options.serial)
    try:
        with http.server.ThreadingHTTPServer(("127.0.0.1", options.port), InspectorHandler) as server:
            server.devices = devices
            url = f"http://127.0.0.1:{server.server_port}/"
            print(f"Network Inspector for {options.application_id}\n{url}\nPress Ctrl+C to stop.", flush=True)
            if not options.no_open:
                webbrowser.open(url)
            server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except OSError as error:
        print(f"Could not start Network Inspector: {error}", file=sys.stderr)
        return 1
    finally:
        devices.close()


if __name__ == "__main__":
    sys.exit(main())
