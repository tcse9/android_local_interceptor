"use strict";

const byId = (id) => document.getElementById(id);
const text = (tag, value, className) => {
  const element = document.createElement(tag);
  element.textContent = value;
  if (className) element.className = className;
  return element;
};

let calls = [];
let selected = null;
let details = null;
let paused = false;
let generation = 0;

async function get(path, options) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error);
  return data;
}

function setConnection(message, mode, notice = "") {
  byId("connection").textContent = message;
  byId("connection").dataset.mode = mode;
  byId("notice").textContent = notice;
  byId("notice").hidden = !notice;
}

function statusLabel(call) {
  return call.status ?? (call.phase === "Failed" ? "Failed" : "Pending");
}

function statusKind(call) {
  if (call.phase === "Failed" || call.status >= 400) return "error";
  return call.status ? "ok" : "waiting";
}

function renderCalls() {
  const list = byId("calls");
  const focused = list.contains(document.activeElement) ? document.activeElement.dataset.id : null;
  const filter = byId("filter").value.trim().toLowerCase();
  const filtered = calls.filter((call) =>
    [call.method, call.url, statusLabel(call)].some((value) => String(value).toLowerCase().includes(filter))
  );
  list.replaceChildren(...filtered.map((call) => {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "call";
    row.dataset.id = call.id;
    row.setAttribute("aria-pressed", String(call.id === selected));
    row.title = call.url;
    row.addEventListener("click", () => select(call.id));
    const name = text("span", "", "call-text");
    name.append(text("span", call.method, "method"), text("span", call.url, "call-url"));
    const status = text("span", statusLabel(call), "status");
    status.dataset.kind = statusKind(call);
    row.append(name, status, text("span", (call.elapsedMs ?? call.headersMs) == null
      ? "—" : `${call.elapsedMs ?? call.headersMs} ms`));
    return row;
  }));
  if (focused) list.querySelector(`[data-id="${focused}"]`)?.focus({preventScroll: true});
  byId("count").textContent = filter ? `${filtered.length} / ${calls.length}` : calls.length;
  byId("empty").hidden = filtered.length > 0;
  byId("empty").textContent = filter ? "No calls match this filter." : "Waiting for network calls.";
}

function bodyLabel(body) {
  switch (body.state) {
    case "none": return "No body";
    case "duplex": return "Duplex request body omitted";
    case "waiting": return "Waiting for body";
    case "unavailable": return "Body unavailable";
    case "partial": return "Partial body";
    case "reading": return "Reading body";
    default: return "Body";
  }
}

function formatBody(body) {
  if (body.text === undefined) return body.base64;
  if (!body.truncated && body.state === "complete" && /(?:\/|\+)json\b/i.test(body.mediaType ?? "")) {
    try { return JSON.stringify(JSON.parse(body.text), null, 2); } catch { /* Display original text. */ }
  }
  return body.text;
}

function renderPanel(target, headers, body) {
  const previous = target.querySelectorAll("details");
  const headerPanel = document.createElement("details");
  headerPanel.open = previous[0]?.open ?? true;
  headerPanel.append(text("summary", `Headers (${headers.length})`));
  if (headers.length) {
    const pairs = text("dl", "", "header-list");
    for (const [name, value] of headers) {
      const row = document.createElement("div");
      row.append(text("dt", name), text("dd", value));
      pairs.append(row);
    }
    headerPanel.append(pairs);
  }
  const bodyPanel = document.createElement("details");
  bodyPanel.open = previous[1]?.open ?? true;
  const bytes = body.truncated ? `${body.capturedBytes} of ${body.bytes} bytes` : `${body.bytes} bytes`;
  let label = bodyLabel(body);
  if (body.bytes || ["partial", "reading", "complete"].includes(body.state)) label += ` · ${bytes}`;
  if (body.base64 && body.text === undefined) label += " · Base64";
  bodyPanel.append(text("summary", label));
  if (body.capturedBytes) bodyPanel.append(text("pre", formatBody(body)));
  target.replaceChildren(headerPanel, bodyPanel);
}

function shellQuote(value) {
  return "'" + value.replaceAll("'", "'\"'\"'") + "'";
}

const transportHeaders = new Set([
  "connection", "content-length", "transfer-encoding", "proxy-connection", "keep-alive", "upgrade", "expect",
]);

function curlCommand(call) {
  const body = call.requestBody;
  if (body.state !== "none" && (body.state !== "complete" || body.truncated)) return "";
  const args = ["curl", "--globoff", "--request", shellQuote(call.method), "--url", shellQuote(call.url)];
  const connectionHeaders = call.requestHeaders
    .filter(([name]) => name.toLowerCase() === "connection")
    .flatMap(([, value]) => value.split(",").map((name) => name.trim().toLowerCase()));
  for (const [name, value, redacted] of call.requestHeaders) {
    const lower = name.toLowerCase();
    if (!redacted && !transportHeaders.has(lower) && !connectionHeaders.includes(lower)) {
      args.push("--header", shellQuote(value ? `${name}: ${value}` : `${name};`));
    }
  }
  if (body.state === "none") return args.join(" ");
  if (!call.requestHeaders.some(([name]) => name.toLowerCase() === "content-type")) {
    args.push("--header", shellQuote("Content-Type:"));
  }
  args.push("--data-binary", body.base64 ? "@-" : "''");
  const request = args.join(" ");
  return body.base64
    ? `printf %s ${shellQuote(body.base64)} | openssl base64 -d -A | ${request}`
    : request;
}

function renderDetails(call) {
  byId("detail-content").hidden = !call;
  byId("detail-empty").hidden = Boolean(call);
  byId("detail-empty").textContent = selected == null
    ? "Select a request to view its headers, bodies, and cURL command." : "Loading call details…";
  if (!call) return;
  const status = text("span", statusLabel(call), "status");
  status.dataset.kind = statusKind(call);
  byId("detail-meta").replaceChildren(text("span", call.method, "method"), status, text("span", call.phase));
  byId("detail-title").textContent = call.url;
  const info = [new Date(call.startedAt).toLocaleTimeString(), call.protocol];
  if (call.headersMs != null) info.push(`Headers in ${call.headersMs} ms`);
  if (call.elapsedMs != null) info.push(`${call.elapsedMs} ms total`);
  if (call.responseUrl && call.responseUrl !== call.url) info.push(`Final URL ${call.responseUrl}`);
  if (call.error) info.push(call.error);
  byId("detail-info").textContent = info.filter(Boolean).join(" · ");
  renderPanel(byId("request"), call.requestHeaders, call.requestBody);
  renderPanel(byId("response"), call.responseHeaders, call.responseBody);
  const command = curlCommand(call);
  byId("copy").disabled = !command;
  byId("curl-panel").hidden = !command;
  byId("curl-text").textContent = command;
  byId("copy-note").textContent = !command ? "cURL export requires the complete request body."
    : call.requestHeaders.some(([, , redacted]) => redacted) ? "Redacted headers are omitted from the cURL command." : "";
}

async function loadDetails(id, version) {
  try {
    const call = await get(`/api/calls/${id}`);
    if (version === generation && selected === id && JSON.stringify(details) !== JSON.stringify(call)) {
      details = call;
      renderDetails(call);
    }
  } catch (error) {
    if (version === generation && selected === id) {
      setConnection("Details unavailable", "offline", error.message);
      if (!details) byId("detail-empty").textContent = error.message;
    }
  }
}

function select(id) {
  if (selected === id && details) return;
  selected = id;
  details = null;
  generation++;
  renderCalls();
  renderDetails(null);
  if (id != null) loadDetails(id, generation);
}

async function refresh(wait = 1000) {
  try {
    if (!paused) {
      const version = generation;
      const next = await get("/api/calls");
      if (version === generation && !paused) {
        if (JSON.stringify(calls) !== JSON.stringify(next)) {
          calls = next;
          if (selected != null && !calls.some((call) => call.id === selected)) select(null);
          else renderCalls();
        }
        setConnection("Connected", "connected");
        if (selected != null) await loadDetails(selected, generation);
      }
    }
  } catch (error) {
    if (!paused) setConnection("Disconnected", "offline", error.message);
  } finally {
    setTimeout(refresh, wait);
  }
}

byId("filter").addEventListener("input", renderCalls);
byId("pause").addEventListener("click", () => {
  paused = !paused;
  generation++;
  byId("pause").textContent = paused ? "Resume" : "Pause";
  byId("pause").setAttribute("aria-pressed", String(paused));
  setConnection(paused ? "Paused" : "Connecting", "");
});
byId("clear").addEventListener("click", async () => {
  try {
    await get("/api/clear", {method: "POST", headers: {"X-Network-Inspector": "1"}});
    generation++;
    calls = [];
    select(null);
  } catch (error) {
    setConnection("Could not clear", "offline", error.message);
  }
});
byId("copy").addEventListener("click", async () => {
  const command = byId("curl-text").textContent;
  try {
    await navigator.clipboard.writeText(command);
    byId("copy-note").textContent = details?.requestHeaders.some(([, , redacted]) => redacted)
      ? "Copied. Redacted headers were omitted." : "Copied to clipboard.";
  } catch {
    byId("curl-panel").open = true;
    byId("copy-note").textContent = "Clipboard unavailable. Select the command below to copy it.";
  }
});
refresh();
