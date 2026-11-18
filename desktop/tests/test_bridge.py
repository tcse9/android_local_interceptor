import base64
import http.server
import importlib.util
import json
from pathlib import Path
import socket
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("inspector", Path(__file__).resolve().parents[1] / "inspect.py")
inspector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspector)


class Echo(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        value = self.rfile.read(int(self.headers["Content-Length"]))
        self.send_response(201)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("X-Test", "one")
        self.send_header("X-Test", "two")
        self.end_headers()
        self.wfile.write(value)

    def log_message(self, *args):
        pass


class BridgeTests(unittest.TestCase):
    def test_replay_preserves_bytes_and_duplicate_headers(self):
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Echo) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                payload = '{"hello":"বাংলা"}'.encode()
                result = inspector.replay_request({
                    "method": "POST", "url": f"http://127.0.0.1:{server.server_port}/echo",
                    "headers": [["Content-Type", "application/json"], ["Content-Length", "99999"]],
                    "bodyBase64": base64.b64encode(payload).decode()})
                self.assertEqual(result["status"], 201)
                self.assertEqual(base64.b64decode(result["responseBody"]["base64"]), payload)
                self.assertEqual([x[1] for x in result["responseHeaders"] if x[0] == "X-Test"], ["one", "two"])
            finally:
                server.shutdown()
                worker.join()

    def test_replay_rejects_invalid_inputs(self):
        for data in [
            {"method": "POST", "url": "file:///tmp/test"},
            {"method": "GET", "url": "http://example.com", "bodyBase64": "eA=="},
            {"method": "POST\r\n", "url": "http://example.com"},
            {"method": "POST", "url": "http://example.com", "bodyBase64": "%%%"},
        ]:
            with self.assertRaises(ValueError):
                inspector.replay_request(data)

    def test_device_selection_and_forward_isolation(self):
        devices = inspector.Devices("adb", "com.example.app")
        inventory = {"devices": [{"serial": "one", "state": "device"},
                                 {"serial": "two", "state": "device"},
                                 {"serial": "locked", "state": "unauthorized"}]}
        with patch.object(devices, "inventory", return_value=inventory):
            with self.assertRaises(ValueError):
                devices.target({})
            with self.assertRaises(ValueError):
                devices.target({"serial": ["locked"]})
            self.assertEqual(devices.target({"serial": ["two"]}), ("two", "com.example.app"))
            with patch.object(devices, "run", side_effect=["10001", "10002", "", ""]) as run:
                self.assertEqual(devices.port(("one", "com.example.app")), 10001)
                self.assertEqual(devices.port(("two", "com.example.app")), 10002)
                self.assertEqual(devices.port(("one", "com.example.app")), 10001)
                self.assertEqual(run.call_count, 2)
                devices.close()
                self.assertEqual(run.call_count, 4)

    def test_browser_origin_and_host_checks(self):
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), inspector.InspectorHandler) as server:
            server.devices = inspector.Devices("adb", "com.example.app")
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            url = f"http://127.0.0.1:{server.server_port}"
            try:
                with self.assertRaises(HTTPError) as denied:
                    urlopen(Request(url + "/api/replay", data=b"{}", headers={
                        "X-Network-Inspector": "1", "Origin": "https://example.com"}))
                self.assertEqual(denied.exception.code, 403)
                with self.assertRaises(HTTPError) as denied:
                    urlopen(Request(url + "/", headers={"Host": "example.com"}))
                self.assertEqual(denied.exception.code, 403)
                with self.assertRaises(HTTPError) as denied:
                    urlopen(Request(url + "/api/replay", data=b"[]", headers={"X-Network-Inspector": "1"}))
                self.assertEqual(denied.exception.code, 400)
            finally:
                server.shutdown()
                worker.join()


if __name__ == "__main__":
    unittest.main()
