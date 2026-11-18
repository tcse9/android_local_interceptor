"""Local fixture for the optional headless browser workflow check."""
import base64
import http.server
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("inspector", Path(__file__).resolve().parents[1] / "inspect.py")
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


def body(value):
    data = value.encode()
    return {"text": value, "base64": base64.b64encode(data).decode(), "bytes": len(data),
            "capturedBytes": len(data), "state": "complete", "truncated": False, "mediaType": "application/json"}


class FixtureDevices:
    def inventory(self):
        return {"applicationId": "com.example.app", "serial": "one",
                "devices": [{"serial": item, "state": "device", "name": "Phone " + item} for item in ("one", "two")]}

    def command(self, query, command):
        serial = query.get("serial", ["one"])[0]
        result = {"id": 1, "method": "POST", "url": "https://example.com/" + serial,
                  "status": 201, "startedAt": 1000, "phase": "Finished", "elapsedMs": 120,
                  "requestHeaders": [["Content-Type", "application/json", False]],
                  "responseHeaders": [], "requestBody": body('{"device":"' + serial + '","secret":"hello"}'),
                  "responseBody": body('{"ok":true}')}
        if command == "list":
            return [result]
        if command.startswith("call "):
            return result
        if command == "rules":
            return []
        return {}


class FixtureHandler(inspector.InspectorHandler):
    def do_POST(self):
        if self.path == "/echo":
            payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            self.reply(200, payload)
        else:
            super().do_POST()


if __name__ == "__main__":
    with http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler) as server:
        server.devices = FixtureDevices()
        print(f"http://127.0.0.1:{server.server_port}/", flush=True)
        server.serve_forever()
