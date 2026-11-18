"use strict";

/* File formats and byte conversions are shared by the UI and format checks. */
const InspectorFormats = (() => {
  const secrets = new Set(["authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"]);
  function encode(value) {
    return btoa(Array.from(new TextEncoder().encode(value), byte => String.fromCharCode(byte)).join(""));
  }
  function body(value = "", state = "complete", mediaType = "application/json") {
    const base64 = encode(value);
    const size = new TextEncoder().encode(value).length;
    return {text: value, base64, state, mediaType, bytes: size, capturedBytes: size, truncated: false};
  }
  function validateCapture(value) {
    if (!value || typeof value !== "object" || typeof value.method !== "string" || typeof value.url !== "string") {
      throw new Error("Invalid capture");
    }
    const result = {};
    for (const key of ["method", "url", "phase", "error", "protocol", "responseUrl", "source", "notes"]) {
      if (value[key] != null) result[key] = String(value[key]).slice(0, 2000000);
    }
    for (const key of ["status", "startedAt", "elapsedMs", "headersMs"]) {
      if (value[key] != null && Number.isFinite(Number(value[key]))) result[key] = Number(value[key]);
    }
    for (const key of ["requestHeaders", "responseHeaders"]) {
      if (!Array.isArray(value[key]) || value[key].length > 500) throw new Error("Invalid headers");
      result[key] = value[key].map(pair => {
        if (!Array.isArray(pair) || typeof pair[0] !== "string" || typeof pair[1] !== "string") throw new Error("Invalid header");
        return [pair[0], pair[1], Boolean(pair[2])];
      });
    }
    for (const key of ["requestBody", "responseBody"]) {
      const item = value[key];
      if (!item || typeof item.base64 !== "string" || item.base64.length > 1500000) throw new Error("Invalid body");
      atob(item.base64);
      result[key] = {base64: item.base64, state: String(item.state ?? "partial"),
        mediaType: String(item.mediaType ?? ""), bytes: Number(item.bytes) || 0,
        capturedBytes: atob(item.base64).length, truncated: Boolean(item.truncated)};
      if (typeof item.text === "string") result[key].text = item.text;
    }
    if (Array.isArray(value.timings)) result.timings = value.timings.slice(0, 500)
      .filter(item => item && typeof item.name === "string" && Number.isFinite(item.elapsedMs))
      .map(item => ({name: item.name, elapsedMs: item.elapsedMs}));
    if (Array.isArray(value.messages)) result.messages = value.messages.slice(0, 200)
      .filter(item => item && typeof item.direction === "string")
      .map(item => ({direction: item.direction, kind: String(item.kind), bytes: Number(item.bytes) || 0,
        truncated: Boolean(item.truncated), text: typeof item.text === "string" ? item.text : undefined,
        base64: typeof item.base64 === "string" ? item.base64 : ""}));
    return result;
  }
  function parseSession(data) {
    if (data?.format !== "network-inspector-session" || data.version !== 1 || !Array.isArray(data.calls) || data.calls.length > 500) {
      throw new Error("Expected an inspector session with up to 500 calls");
    }
    return data.calls.map(validateCapture);
  }
  function redact(call, includeSecrets, fields) {
    const result = validateCapture(call);
    for (const key of ["requestHeaders", "responseHeaders"]) {
      result[key] = result[key].map(([name, value, hidden]) =>
        [name, hidden || (!includeSecrets && secrets.has(name.toLowerCase())) ? "[redacted]" : value,
          hidden || (!includeSecrets && secrets.has(name.toLowerCase()))]);
    }
    const names = new Set(fields.map(value => value.toLowerCase()));
    function visit(value) {
      if (Array.isArray(value)) return value.map(visit);
      if (!value || typeof value !== "object") return value;
      return Object.fromEntries(Object.entries(value).map(([key, item]) =>
        [key, names.has(key.toLowerCase()) ? "[redacted]" : visit(item)]));
    }
    for (const key of ["url", "responseUrl"]) {
      try {
        const url = new URL(result[key]);
        for (const name of [...url.searchParams.keys()]) if (names.has(name.toLowerCase())) url.searchParams.set(name, "[redacted]");
        result[key] = url.toString();
      } catch { /* Keep unresolved imported URL templates intact. */ }
    }
    for (const key of ["requestBody", "responseBody"]) {
      const original = result[key];
      if (original.text && fields.length) {
        try {
          result[key] = {...body(JSON.stringify(visit(JSON.parse(original.text))), original.state, original.mediaType),
            truncated: original.truncated};
        } catch { throw new Error("Body-field redaction requires complete JSON bodies. Clear field names or select other calls."); }
      }
    }
    // Frame contents and diagnostic error text may contain credentials or payloads.
    if (!includeSecrets) {
      delete result.messages;
      delete result.error;
      delete result.notes;
    }
    return result;
  }
  function collection(calls) {
    const variables = new Map();
    const item = calls.map((call, index) => {
      if (call.method === "WS") throw new Error("Use session export for WebSocket captures.");
      const captured = call.requestBody;
      if (!["none", "complete"].includes(captured.state) || captured.truncated) {
        throw new Error("Postman export requires complete request bodies. Export a session to preserve partial captures.");
      }
      if (captured.base64 && (typeof captured.text !== "string" || encode(captured.text) !== captured.base64)) {
        throw new Error("Postman raw-body export requires UTF-8 text. Use session export for binary request bodies.");
      }
      const header = call.requestHeaders.map(([key, value, hidden]) => {
        if (hidden) {
          const variable = key.toLowerCase().replace(/[^a-z0-9]/g, "_");
          variables.set(variable, "");
          return {key, value: "{{" + variable + "}}", description: "Fill in this redacted value before replay."};
        }
        return {key, value};
      }).filter(h => !["content-length", "transfer-encoding", "connection", "host"].includes(h.key.toLowerCase()));
      const request = {method: call.method, url: call.url, header};
      if (captured.state !== "none") request.body = {mode: "raw", raw: captured.text ?? "",
        options: {raw: {language: /json/i.test(captured.mediaType) ? "json" : "text"}}};
      const response = [];
      if (call.status) response.push({name: "Captured response", originalRequest: request,
        status: String(call.status), code: call.status,
        header: call.responseHeaders.filter(([, , hidden]) => !hidden).map(([key, value]) => ({key, value})),
        body: call.responseBody.text ?? "", cookie: [], _postman_previewlanguage: "json"});
      return {name: call.method + " " + call.url, request, response};
    });
    return {info: {name: "Network Inspector", schema: "https://schema.getpostman.com/json/collection/v2.1.0/"},
      variable: [...variables].map(([key, value]) => ({key, value, type: "string"})), item};
  }
  function importCollection(data) {
    if (!data?.info || !Array.isArray(data.item)) throw new Error("Expected a Postman collection");
    const result = [];
    function walk(items, parentVariables, parentAuth, depth) {
      if (depth > 20) throw new Error("Postman folders exceed 20 levels");
      for (const item of items) {
        if (result.length >= 500) throw new Error("Import is limited to 500 requests");
        const vars = {...parentVariables};
        for (const variable of item.variable ?? []) if (!variable.disabled) vars[variable.key] = variable.value;
        const resolve = value => String(value ?? "").replace(/{{([^{}]+)}}/g, (full, key) =>
          Object.hasOwn(vars, key) ? String(vars[key]) : full);
        const auth = item.auth ?? parentAuth;
        if (Array.isArray(item.item)) { walk(item.item, vars, auth, depth + 1); continue; }
        if (!item.request) continue;
        const req = typeof item.request === "string" ? {url: item.request, method: "GET"} : item.request;
        const actualAuth = req.auth ?? auth;
        const notes = [];
        const header = (req.header ?? []).filter(h => !h.disabled).map(h => [String(h.key), resolve(h.value), false]);
        const attributes = Object.fromEntries((actualAuth?.[actualAuth.type] ?? []).map(x => [x.key, resolve(x.value)]));
        if (actualAuth?.type === "bearer") header.push(["Authorization", "Bearer " + (attributes.token ?? ""), false]);
        else if (actualAuth?.type === "basic") header.push(["Authorization", "Basic " + encode((attributes.username ?? "") + ":" + (attributes.password ?? "")), false]);
        else if (actualAuth && actualAuth.type !== "noauth") notes.push("Configure imported auth manually: " + actualAuth.type);
        let url = resolve(typeof req.url === "string" ? req.url : req.url?.raw);
        if (!url && req.url) {
          const value = req.url;
          const host = Array.isArray(value.host) ? value.host.join(".") : value.host;
          const path = Array.isArray(value.path) ? value.path.join("/") : value.path ?? "";
          url = resolve((value.protocol ?? "https") + "://" + host + (value.port ? ":" + value.port : "") + "/" + path);
        }
        let value = "";
        let mode = "none";
        if (req.body && !req.body.disabled) {
          mode = "complete";
          if (req.body.mode === "raw") {
            value = resolve(req.body.raw);
            if (req.body.options?.raw?.language === "json" && !header.some(([key]) => key.toLowerCase() === "content-type")) {
              header.push(["Content-Type", "application/json", false]);
            }
          }
          else if (req.body.mode === "urlencoded") {
            value = new URLSearchParams((req.body.urlencoded ?? []).filter(x => !x.disabled).map(x => [resolve(x.key), resolve(x.value)])).toString();
            if (!header.some(([name]) => name.toLowerCase() === "content-type")) header.push(["Content-Type", "application/x-www-form-urlencoded", false]);
          } else {
            mode = "unavailable";
            notes.push("Replace unsupported body mode before replay: " + req.body.mode);
          }
        }
        if (req.url?.query && typeof req.url !== "string") {
          const query = req.url.query.filter(x => !x.disabled).map(x => [resolve(x.key), resolve(x.value)]);
          url = url.split("?")[0] + (query.length ? "?" + new URLSearchParams(query) : "");
        }
        const example = item.response?.[0];
        result.push({method: req.method ?? "GET", url, requestHeaders: header,
          requestBody: body(value, mode, header.find(([key]) => key.toLowerCase() === "content-type")?.[1] ?? "text/plain"),
          responseHeaders: (example?.header ?? []).map(h => [String(h.key), String(h.value), false]),
          responseBody: body(example?.body ?? "", example ? "complete" : "none"),
          status: example?.code, startedAt: Date.now(), phase: "Imported",
          source: "Postman", notes: [...notes, "Postman scripts are not executed. Resolve any {{variables}} before sending."].join(" ")});
      }
    }
    const variables = {};
    for (const variable of data.variable ?? []) if (!variable.disabled) variables[variable.key] = variable.value;
    walk(data.item, variables, data.auth, 0);
    return result.map(validateCapture);
  }
  return {encode, body, validateCapture, parseSession, redact, collection, importCollection};
})();
