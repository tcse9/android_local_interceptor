const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const context = {TextEncoder, btoa, atob, URL, URLSearchParams};
vm.createContext(context);
vm.runInContext(fs.readFileSync("desktop/formats.js", "utf8") + ";this.formats = InspectorFormats;", context);
const f = context.formats;
const call = {method: "POST", url: "https://example.com/api?token=private", status: 200,
  requestHeaders: [["Authorization", "Bearer secret", false], ["Proxy-Authorization", "[redacted]", true],
    ["Content-Type", "application/json", false]],
  responseHeaders: [["Set-Cookie", "session=secret", false]],
  requestBody: f.body('{"text":"বাংলা","password":"secret"}'),
  responseBody: f.body('{"ok":true}'), phase: "Finished", startedAt: 123};

const safe = f.redact(call, false, ["password", "token"]);
assert.equal(safe.requestHeaders[0][1], "[redacted]");
assert.equal(safe.responseHeaders[0][1], "[redacted]");
assert.equal(JSON.parse(safe.requestBody.text).password, "[redacted]");
assert.ok(!safe.url.includes("private"));
const collection = f.collection([safe]);
const imported = f.importCollection(collection);
assert.equal(imported.length, 1);
assert.equal(imported[0].requestBody.text, safe.requestBody.text);
assert.equal(imported[0].responseBody.text, call.responseBody.text);
assert.equal(imported[0].status, 200);
assert.ok(collection.variable.some(v => v.key === "authorization"));
assert.equal(f.parseSession({format: "network-inspector-session", version: 1, calls: [call]}).length, 1);

const nested = {info: {}, variable: [{key: "base", value: "https://example.com"}],
  auth: {type: "bearer", bearer: [{key: "token", value: "actual"}]},
  item: [{name: "folder", item: [{request: {method: "POST", url: "{{base}}/echo",
    header: [{key: "Disabled", value: "skip", disabled: true}],
    body: {mode: "urlencoded", urlencoded: [{key: "name", value: "a b"}]}}}]}]};
const form = f.importCollection(nested)[0];
assert.equal(form.url, "https://example.com/echo");
assert.equal(form.requestBody.text, "name=a+b");
assert.equal(form.requestHeaders.find(x => x[0] === "Authorization")[1], "Bearer actual");
assert.ok(!form.requestHeaders.some(x => x[0] === "Disabled"));
assert.throws(() => f.collection([{...call, requestBody: {...call.requestBody, truncated: true}}]));
assert.throws(() => f.collection([{...call, requestBody: {base64: "/w==", state: "complete"}}]));
assert.throws(() => f.parseSession({format: "wrong", calls: []}));
const unsupported = f.importCollection({info: {}, item: [{request: {method: "POST", url: "https://example.com",
  body: {mode: "file", file: {src: "/tmp/file"}}}}]})[0];
assert.equal(unsupported.requestBody.state, "unavailable");
assert.match(unsupported.notes, /unsupported body mode/);
console.log("Format checks passed: redaction, Postman folders/auth/variables/forms, sessions, Unicode, and unsupported bodies.");
