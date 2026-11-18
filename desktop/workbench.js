"use strict";

let exportContents = null;
let searchVersion = 0;
let replayBusy = false;
let exportVersion = 0;

async function post(path, data) {
  return get(path, {method: "POST", headers: {"X-Network-Inspector": "1", "Content-Type": "application/json"},
    body: JSON.stringify(data)});
}

function resetTarget() {
  generation++;
  exportVersion++;
  searchVersion++;
  calls = source === "saved" ? savedCalls : [];
  captures.clear();
  if (source === "saved") for (const call of calls) captures.set(call.id, {call});
  selected = null;
  details = null;
  comparisonCall = null;
  byId("comparison").hidden = true;
  byId("rules-json").value = "[]";
  byId("search-state").textContent = "";
  byId("pin-compare").textContent = "Compare with…";
  renderCalls();
  renderDetails(null);
  setConnection(source === "saved" ? "Imported / replayed" : "Connecting", "");
}

async function updateDevices() {
  try {
    const inventory = await get("/api/devices");
    if (!applicationId) {
      applicationId = inventory.applicationId;
      byId("application-id").value = applicationId;
    }
    const selectedDevice = serial || inventory.serial;
    const selector = byId("device");
    selector.replaceChildren(new Option("Select device", ""));
    for (const device of inventory.devices) {
      const option = new Option(`${device.name} · ${device.serial} · ${device.state}`, device.serial);
      option.disabled = device.state !== "device";
      selector.append(option);
    }
    const online = inventory.devices.filter(item => item.state === "device");
    const next = online.some(item => item.serial === selectedDevice) ? selectedDevice
      : online.length === 1 ? online[0].serial : "";
    selector.value = next;
    if (serial !== next) { serial = next; if (source === "live") resetTarget(); }
    if (!next && source === "live") setConnection("Select device", "", "Connect and authorize a device, then refresh devices.");
  } catch (error) {
    setConnection("Device list unavailable", "offline", error.message);
  }
}

async function capture(call, version = generation) {
  if (source === "saved") return call;
  const fingerprint = JSON.stringify(call);
  const cached = captures.get(call.id);
  if (cached?.fingerprint === fingerprint && ["Finished", "Failed"].includes(call.phase)) return cached.call;
  const result = await get(devicePath(`/api/calls/${call.id}`));
  if (version !== generation) throw new Error("Capture source changed. Retry with the current source.");
  captures.set(call.id, {call: result, fingerprint});
  return result;
}

async function searchPayloads() {
  const run = ++searchVersion;
  const version = generation;
  if (!byId("payload-filter").value.trim()) { renderCalls(); return; }
  byId("search-state").textContent = "Searching captured payloads…";
  let missing = 0;
  for (const call of [...calls]) {
    if (run !== searchVersion || version !== generation) return;
    try { await capture(call, version); } catch { missing++; }
  }
  if (run !== searchVersion || version !== generation) return;
  renderCalls();
  byId("search-state").textContent = missing ? `${missing} calls unavailable or removed during search.`
    : "Search includes captured headers, text bodies, and WebSocket messages.";
}

function renderDiagnostics(call) {
  const content = byId("diagnostic-content");
  content.replaceChildren();
  if (call.notes) content.append(text("p", call.notes));
  if (call.source) content.append(text("p", "Source · " + call.source));
  const timing = text("dl", "", "header-list");
  for (const event of call.timings ?? []) {
    const row = document.createElement("div");
    row.append(text("dt", event.name), text("dd", event.elapsedMs + " ms from start"));
    timing.append(row);
  }
  if (timing.children.length) content.append(timing);
  else content.append(text("p", "Detailed timing requires the inspector event listener on the Android client."));
  for (const message of call.messages ?? []) {
    content.append(text("p", `${message.direction} · ${message.kind} · ${message.bytes} bytes${message.truncated ? " · truncated" : ""}`));
    content.append(highlightedBody(message.text ?? message.base64 ?? ""));
  }
}

function renderComparison(call) {
  byId("comparison").hidden = !comparisonCall || comparisonCall.id === call.id;
  if (!comparisonCall || comparisonCall.id === call.id) return;
  function lines(item) {
    return JSON.stringify({method: item.method, url: item.url, status: item.status,
      requestHeaders: item.requestHeaders, requestBody: item.requestBody.text ?? item.requestBody.base64,
      responseHeaders: item.responseHeaders, responseBody: item.responseBody.text ?? item.responseBody.base64}, null, 2).split("\n");
  }
  const before = lines(comparisonCall);
  const after = lines(call);
  const result = [];
  for (let i = 0; i < Math.max(before.length, after.length); i++) {
    if (before[i] === after[i]) continue;
    if (before[i] !== undefined) result.push("- " + before[i]);
    if (after[i] !== undefined) result.push("+ " + after[i]);
    if (result.length > 4000) { result.push("Comparison preview limited to 4000 changed lines."); break; }
  }
  byId("comparison-text").textContent = result.join("\n") || "Compared fields are identical.";
}

function addSaved(items) {
  for (const item of items) {
    item.id = "saved-" + crypto.randomUUID();
    savedCalls.unshift(item);
  }
  savedCalls = savedCalls.slice(0, 500);
  source = "saved";
  byId("source").value = source;
  resetTarget();
  if (items.length) select(items[items.length - 1].id);
}

byId("device").addEventListener("change", () => {
  serial = byId("device").value;
  source = "live";
  byId("source").value = source;
  resetTarget();
});
byId("refresh-devices").addEventListener("click", updateDevices);
byId("application-id").addEventListener("change", () => {
  applicationId = byId("application-id").value.trim();
  resetTarget();
});
byId("source").addEventListener("change", () => {
  source = byId("source").value;
  resetTarget();
});
for (const id of ["method-filter", "status-filter", "duration-filter"]) byId(id).addEventListener("input", renderCalls);
let searchTimer;
byId("payload-filter").addEventListener("input", () => {
  searchVersion++;
  clearTimeout(searchTimer);
  searchTimer = setTimeout(searchPayloads, 250);
});
byId("pin-compare").addEventListener("click", () => {
  if (!details) return;
  comparisonCall = structuredClone(details);
  byId("pin-compare").textContent = "Comparison pinned";
  byId("copy-note").textContent = "Select another call to compare with this request.";
});
byId("import-file").addEventListener("click", () => byId("file").click());
byId("file").addEventListener("change", async event => {
  const file = event.target.files[0];
  try {
    if (!file) return;
    if (file.size > 32 * 1024 * 1024) throw new Error("Import files must be under 32 MiB");
    const data = JSON.parse(await file.text());
    const items = data.format === "network-inspector-session"
      ? InspectorFormats.parseSession(data) : InspectorFormats.importCollection(data);
    if (!items.length) throw new Error("No requests found");
    addSaved(items);
    setConnection(`Imported ${items.length} calls`, "");
  } catch (error) {
    setConnection("Import failed", "offline", error.message);
  } finally {
    event.target.value = "";
  }
});
byId("export-file").addEventListener("click", () => {
  byId("export-preview").textContent = "";
  byId("download-export").disabled = true;
  byId("export-dialog").showModal();
});
for (const id of ["export-format", "export-scope", "export-secrets", "redact-fields"]) {
  byId(id).addEventListener("input", () => {
    exportVersion++;
    exportContents = null;
    byId("download-export").disabled = true;
    byId("export-preview").textContent = "";
  });
}
byId("preview-export").addEventListener("click", async () => {
  const button = byId("preview-export");
  button.disabled = true;
  const previewVersion = ++exportVersion;
  byId("download-export").disabled = true;
  try {
    const version = generation;
    const items = byId("export-scope").value === "selected" ? calls.filter(call => call.id === selected) : visibleCalls();
    if (!items.length) throw new Error("Select calls to export");
    const include = byId("export-secrets").checked;
    const fields = byId("redact-fields").value.split(",").map(x => x.trim()).filter(Boolean);
    const records = [];
    for (const call of items) records.push(InspectorFormats.redact(await capture(call, version), include, fields));
    const format = byId("export-format").value;
    const data = format === "postman" ? InspectorFormats.collection(records)
      : {format: "network-inspector-session", version: 1, exportedAt: new Date().toISOString(), calls: records};
    if (previewVersion !== exportVersion) throw new Error("Export settings changed. Preview again.");
    exportContents = JSON.stringify(data, null, 2);
    if (new TextEncoder().encode(exportContents).length > 32 * 1024 * 1024) {
      exportContents = null;
      throw new Error("Export exceeds 32 MiB. Filter the capture list or export a selected call.");
    }
    byId("export-preview").textContent = exportContents;
    byId("download-export").disabled = false;
    byId("export-note").textContent = `Review ${records.length} calls below. Headers, URLs, and bodies shown here will be included.`;
  } catch (error) {
    byId("export-note").textContent = error.message;
  } finally {
    button.disabled = false;
  }
});
byId("download-export").addEventListener("click", () => {
  if (!exportContents) return;
  const url = URL.createObjectURL(new Blob([exportContents], {type: "application/json"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = byId("export-format").value === "postman" ? "inspector.postman_collection.json" : "inspector-session.json";
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
byId("close-export").addEventListener("click", () => byId("export-dialog").close());
byId("export-dialog").addEventListener("close", () => {
  exportVersion++;
  exportContents = null;
  byId("export-preview").textContent = "";
  byId("download-export").disabled = true;
});

byId("edit-replay").addEventListener("click", () => {
  if (!details || replayBusy) return;
  const body = details.requestBody;
  byId("replay-method").value = details.method === "WS" ? "GET" : details.method;
  byId("replay-url").value = details.url;
  byId("replay-headers").value = details.requestHeaders.filter(([name, , hidden]) =>
    !hidden && !transportHeaders.has(name.toLowerCase())).map(([name, value]) => name + ": " + value).join("\n");
  byId("replay-encoding").value = body.text === undefined && body.base64 ? "base64" : "text";
  byId("replay-body").value = body.text ?? body.base64 ?? "";
  byId("replay-skip-tls").checked = false;
  byId("replay-note").textContent = !["complete", "none"].includes(body.state) || body.truncated
    ? "Only a partial request body is available. Complete or replace it before sending."
    : details.notes ?? "Check authentication headers before sending.";
  byId("replay-dialog").showModal();
});
byId("close-replay").addEventListener("click", () => byId("replay-dialog").close());
byId("replay-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (replayBusy) return;
  const method = byId("replay-method").value.trim();
  const url = byId("replay-url").value.trim();
  const value = byId("replay-body").value;
  const encoding = byId("replay-encoding").value;
  let body;
  try {
    if (url.includes("{{") || value.includes("{{") || byId("replay-headers").value.includes("{{")) {
      throw new Error("Replace Postman {{variables}} before sending.");
    }
    body = encoding === "base64" ? {base64: value, state: "complete", mediaType: "",
      bytes: atob(value).length, capturedBytes: atob(value).length, truncated: false}
      : InspectorFormats.body(value, value || !["GET", "HEAD"].includes(method) ? "complete" : "none", "text/plain");
    const headers = byId("replay-headers").value.split("\n").filter(line => line.trim()).map(line => {
      const index = line.indexOf(":");
      if (index < 1) throw new Error("Each header needs a name followed by a colon and its value.");
      return [line.slice(0, index).trim(), line.slice(index + 1).trim()];
    });
    replayBusy = true;
    byId("send-replay").disabled = true;
    byId("replay-note").textContent = "Sending from this computer…";
    const startedAt = Date.now();
    const result = await post("/api/replay", {method, url, headers, bodyBase64: body.base64,
      skipTlsVerification: byId("replay-skip-tls").checked});
    addSaved([{...result, method, url, requestHeaders: headers.map(pair => [...pair, false]),
      requestBody: body, startedAt}]);
    byId("replay-note").textContent = `Received HTTP ${result.status}. Response is in Imported / replayed.`;
  } catch (error) {
    byId("replay-note").textContent = error.message;
  } finally {
    replayBusy = false;
    byId("send-replay").disabled = false;
  }
});
byId("load-rules").addEventListener("click", async () => {
  try {
    const result = await get(devicePath("/api/rules"));
    byId("rules-json").value = JSON.stringify(result, null, 2);
  } catch (error) { setConnection("Rules unavailable", "offline", error.message); }
});
byId("save-rules").addEventListener("click", async () => {
  try {
    const rules = JSON.parse(byId("rules-json").value);
    if (!Array.isArray(rules)) throw new Error("Rules must be a JSON array");
    const result = await post(devicePath("/api/rules"), {rules});
    if (result.error) throw new Error(result.error);
    byId("copy-note").textContent = "Rules applied to the selected device and app.";
  } catch (error) { setConnection("Rules failed", "offline", error.message); }
});
updateDevices();
