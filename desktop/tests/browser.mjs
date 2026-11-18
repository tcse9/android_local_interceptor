import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {mkdtempSync, readFileSync, writeFileSync} from "node:fs";
import {createInterface} from "node:readline";

const fixture = spawn(process.env.PYTHON ?? "python3", ["desktop/tests/browser_fixture.py"], {stdio: ["ignore", "pipe", "inherit"]});
const lines = createInterface({input: fixture.stdout});
const base = await new Promise(resolve => lines.once("line", resolve));
const profile = mkdtempSync("/tmp/inspector-browser-");
const chrome = spawn(process.env.CHROME_BINARY ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  ["--headless", "--window-size=1440,1000", "--disable-gpu", "--no-first-run", "--disable-background-networking",
    "--remote-debugging-port=0", "--user-data-dir=" + profile, base], {stdio: "ignore"});
const sleep = () => new Promise(resolve => setTimeout(resolve, 150));
let ws;
try {
  let port;
  for (let i = 0; i < 100 && !port; i++) {
    try { port = readFileSync(profile + "/DevToolsActivePort", "utf8").split(String.fromCharCode(10))[0]; } catch { await sleep(); }
  }
  assert.ok(port, "Chrome did not start");
  const tabs = await (await fetch("http://127.0.0.1:" + port + "/json/list")).json();
  ws = new WebSocket(tabs.find(tab => tab.url === base).webSocketDebuggerUrl);
  await new Promise(resolve => ws.addEventListener("open", resolve, {once: true}));
  let sequence = 0;
  async function cdp(method, params) {
    const id = ++sequence;
    const response = new Promise(resolve => {
      function receive(event) {
        const data = JSON.parse(event.data);
        if (data.id !== id) return;
        ws.removeEventListener("message", receive);
        resolve(data);
      }
      ws.addEventListener("message", receive);
    });
    ws.send(JSON.stringify({id, method, params}));
    const result = await response;
    assert.ok(!result.error, JSON.stringify(result.error));
    return result.result;
  }
  async function evaluate(expression) {
    const value = await cdp("Runtime.evaluate", {expression, returnByValue: true, awaitPromise: true});
    assert.ok(!value.exceptionDetails, JSON.stringify(value.exceptionDetails));
    return value.result.value;
  }
  async function waitFor(expression) {
    for (let i = 0; i < 80; i++) {
      if (await evaluate(expression)) return;
      await sleep();
    }
    throw new Error("Timed out waiting for " + expression);
  }
  await waitFor('document.querySelectorAll(".call").length === 1');
  await evaluate('document.querySelector(".call").click()');
  await waitFor('!document.querySelector("#detail-content").hidden');
  assert.match(await evaluate('document.querySelector("#request").textContent'), /device/);
  assert.ok(await evaluate('document.querySelectorAll("#request .syntax-key").length > 0'));
  assert.equal(await evaluate('document.querySelector("#response button").textContent'), "Copy body");
  await evaluate('document.querySelector("#device").value = "two"; document.querySelector("#device").dispatchEvent(new Event("change"))');
  await waitFor('document.querySelector(".call")?.title.endsWith("/two")');
  await evaluate('document.querySelector(".call").click()');
  await waitFor('document.querySelector("#request").textContent.includes("two")');
  await evaluate('document.querySelector("#payload-filter").value = "hello"; document.querySelector("#payload-filter").dispatchEvent(new Event("input"))');
  await waitFor('document.querySelector("#search-state").textContent.includes("Search includes")');
  assert.equal(await evaluate('document.querySelectorAll(".call").length'), 1);
  await evaluate('document.querySelector("#payload-filter").value = "absent"; document.querySelector("#payload-filter").dispatchEvent(new Event("input"))');
  await waitFor('document.querySelectorAll(".call").length === 0');
  await evaluate('document.querySelector("#payload-filter").value = ""; document.querySelector("#payload-filter").dispatchEvent(new Event("input"))');
  await waitFor('document.querySelectorAll(".call").length === 1');
  await evaluate('document.querySelector("#export-file").click(); document.querySelector("#export-format").value = "postman"; document.querySelector("#preview-export").click()');
  await waitFor('!document.querySelector("#download-export").disabled');
  const exported = JSON.parse(await evaluate('document.querySelector("#export-preview").textContent'));
  assert.equal(exported.item[0].request.url, "https://example.com/two");
  await evaluate('document.querySelector("#close-export").click(); document.querySelector("#edit-replay").click()');
  assert.equal(await evaluate('document.querySelector("#replay-body").value'), '{"device":"two","secret":"hello"}');
  await evaluate(`document.querySelector("#replay-url").value = ${JSON.stringify(base + "echo")}; document.querySelector("#send-replay").click()`);
  await waitFor('document.querySelector("#replay-note").textContent.includes("Received HTTP 200")');
  assert.match(await evaluate('document.querySelector("#response").textContent'), /two/);
  await evaluate('document.querySelector("#close-replay").click()');
  await cdp("Browser.grantPermissions", {origin: base.slice(0, -1), permissions: ["clipboardReadWrite", "clipboardSanitizedWrite"]});
  await evaluate('document.querySelector("#response button").click()');
  await waitFor('document.querySelector("#response button").textContent === "Copied"');
  assert.equal(await evaluate('navigator.clipboard.readText()'), '{"device":"two","secret":"hello"}');
  await evaluate('document.querySelector("#pin-compare").click()');
  await evaluate('addSaved([{method:"GET",url:"https://example.com/comparison",phase:"Imported",requestHeaders:[],responseHeaders:[],requestBody:InspectorFormats.body("", "none"),responseBody:InspectorFormats.body("changed")}])');
  await waitFor('document.querySelector("#detail-title").textContent.endsWith("/comparison")');
  await evaluate('document.querySelector("#pin-compare").click(); select(savedCalls[1].id)');
  await waitFor('!document.querySelector("#comparison").hidden');
  assert.match(await evaluate('document.querySelector("#comparison-text").textContent'), /changed/);
  const screenshot = await cdp("Page.captureScreenshot", {format: "png"});
  writeFileSync("/tmp/inspector-workbench.png", Buffer.from(screenshot.data, "base64"));
  // Verify a real mouse drag rather than a synthetic pointer-capture event.
  const handle = await evaluate('(() => {const r=document.querySelector("#traffic-splitter").getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+50};})()');
  const before = await evaluate('document.querySelector(".traffic").getBoundingClientRect().width');
  await cdp("Input.dispatchMouseEvent", {type: "mousePressed", x: handle.x, y: handle.y, button: "left", clickCount: 1});
  await cdp("Input.dispatchMouseEvent", {type: "mouseMoved", x: handle.x + 80, y: handle.y, button: "left", buttons: 1});
  await cdp("Input.dispatchMouseEvent", {type: "mouseReleased", x: handle.x + 80, y: handle.y, button: "left", clickCount: 1});
  assert.ok(await evaluate('document.querySelector(".traffic").getBoundingClientRect().width') > before + 40);
  await evaluate('addSaved(InspectorFormats.importCollection({info:{},item:[{request:{method:"POST",url:"https://example.com/new",body:{mode:"raw",raw:"test"}}}]}))');
  await waitFor('document.querySelector("#detail-title").textContent.endsWith("/new")');
  await cdp("Emulation.setDeviceMetricsOverride", {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
  assert.equal(await evaluate('getComputedStyle(document.querySelector("#traffic-splitter")).display'), "none");
  assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true);
  console.log("Browser checks passed: device switch, colors/copy, search, Postman preview/import, replay editor, mouse resizing, and mobile layout.");
} finally {
  ws?.close();
  chrome.kill();
  fixture.kill();
  lines.close();
  // Chrome exits asynchronously. Keep its profile in /tmp for browser diagnostics.
}
