# Android Local Network Inspector

A debug-only Android library and a local browser dashboard. Inspect OkHttp calls as they happen, read request and response headers and bodies, and copy a cURL command. The dashboard connects over ADB and runs on your computer. HTTPS is captured inside the app, so it uses the app's existing trust and certificate-pinning configuration.

## Run the demo

Install JDK 17, Android SDK Platform 35, Android SDK Platform-Tools, and Python 3.9 or newer. Set `ANDROID_HOME` or open the project in Android Studio. Start an emulator or connect a device with USB debugging enabled.

```sh
./gradlew :demo:installDebug
adb shell am start -n dev.localinspector.demo/.MainActivity
python3 desktop/inspect.py dev.localinspector.demo
```

Tap **Send GET** or **Send POST** in the demo. Its URL field defaults to a public HTTPS echo endpoint and accepts your own HTTPS endpoint. To try it offline, run `python3 -m http.server --bind 127.0.0.1 8000` from a directory you want to serve, enter `http://10.0.2.2:8000/` in the Android emulator, and tap **Send GET**. The dashboard opens automatically. If you have multiple devices attached, choose one from the dashboard device selector or use `--serial SERIAL` to preselect it. Use `--no-open` to print the URL only or `--adb /path/to/adb` if ADB is outside your PATH.

## Add it to your app

Publish the AAR and its dependency metadata to your local Maven repository.

```sh
./gradlew :inspector:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` under `dependencyResolutionManagement.repositories` in `settings.gradle.kts`, alongside your existing repositories. In the app's `build.gradle.kts`, add:

```kotlin
dependencies {
    debugImplementation("io.github.tcse9:inspector:0.2.0")
}
```

In your debug source set, create one inspector and add its interceptor once to each existing OkHttp client builder. Put this in your application startup or dependency-injection setup before creating Retrofit services or making calls. Register the inspector after any application interceptors that add headers or rewrite request bodies you want to see.

```kotlin
// src/debug, for example in your debug networking module
private val inspector = NetworkInspector()

fun configureDebugNetworking(context: Context, builder: OkHttpClient.Builder) {
    inspector.start(context)
    builder.addInterceptor(inspector.interceptor())
}
```

Import `dev.localinspector.NetworkInspector`. Use the same OkHttp client for your Retrofit services with `Retrofit.Builder().client(client)`. Provide a release counterpart for any shared networking factory, as shown in the demo. The library's minimum Android version is API 21. The demo compiles against API 35, and the library is built against OkHttp 4.12.

Launch your installed debug app, then run the desktop launcher from this project. Use the installed application ID, including any debug suffix.

```sh
python3 desktop/inspect.py com.example.myapp.debug
```

The library starts capture after `start()` and rejects non-debuggable applications. Use one inspector in the app process you want to inspect. The desktop launcher removes its ADB forward on normal exit or Ctrl+C.

## Capture and replay

- Each configured client produces one record per logical OkHttp call, including cache hits and failed requests. Redirects and retries are grouped into the original call, and the dashboard shows the final response URL when it differs. Calls made through WebView, Cronet, HttpURLConnection, native sockets, or other uninstrumented clients are outside this capture path. WebSocket handshakes are visible. WebSocket message capture is available through `inspector.newWebSocket(...)`. Duplex request bodies are outside body capture.
- Response content appears as the app reads it. Early closure is marked partial when the full body cannot be confirmed. Long-lived responses stay readable without waiting for them to finish. The dashboard displays recognized text types and shows Base64 for other types.
- The default buffer holds the latest 150 calls and up to 64 KiB per request and response body. The constructor accepts custom limits and extra header names to redact. Bodies and query parameters may contain sensitive values. Captures stay in memory and are cleared with **Clear** or when the app process ends. Pausing the dashboard pauses its live updates while Android continues capturing.
- Authorization, Proxy-Authorization, Cookie, Set-Cookie, and X-API-Key are redacted. cURL omits redacted and transport-managed headers. For commands that include body bytes, replay requires a POSIX shell, cURL, and OpenSSL. Export is available when the request has no body or its entire body was captured.

## Publish to Maven Central

Follow [PUBLISHING.md](PUBLISHING.md) to prepare a signed release bundle and publish it through the Central Portal.

## Authenticated cURL replay

The default configuration redacts Authorization and Proxy-Authorization before storing a call.
To include Authorization and Proxy-Authorization in the dashboard and copied cURL, create the inspector with
explicit capture enabled in your debug networking configuration.

```kotlin
private val inspector = NetworkInspector(true)
```

Register the inspector after the application interceptor that adds these headers.
Restart the app and trigger a fresh request after changing this setting.
Captured tokens are visible in the dashboard and copied commands.
This constructor is available starting with 0.1.1. With version 0.1.0, add Authorization manually in Postman or your cURL command.

Drag the dividers between traffic and details, or between request and response, to adjust pane widths. Focus a divider and use arrow keys for keyboard resizing. Double-click a divider to reset its width. Narrow screens stack the panes.

Proxy-Authorization capture with this option requires 0.1.2 or newer. Explicit extra redacted header names still take precedence over the capture option.

## Workbench features (0.2.0)

The 0.2.0 source adds timing, mocks, simulation, and WebSocket APIs. Publish this version
before consuming its Maven dependency, or use the local publication workflow above.

### Device selection, filters and search

Start `python3 desktop/inspect.py com.example.app` once and use the Device selector to switch
between online devices. Refresh devices after connecting a device or authorizing USB debugging.
The App field accepts installed application IDs, including debug suffixes.
Each device needs the app running with the inspector initialized.
Captures, detail requests, rule edits, and clear operations use the selected device and app.

Filter by method, status class, failures, pending calls, minimum duration, URL, or text
in captured headers and payloads. Payload search covers retained text previews. Binary
payloads are displayed and copied as Base64. Request and response bodies have independent
copy buttons and JSON syntax colors. Partial and truncated body labels describe the captured portion.

### Postman, sessions and comparisons

Use Import to open a Postman collection v2.1 or an inspector session JSON file.
Imported requests appear under Imported / replayed and can be edited before replay.
Postman folders, collection/folder variables, inherited bearer/basic authentication,
raw text/JSON bodies, URL-encoded forms, and first saved response examples are supported.
Request headers and values preserve duplicates.
Postman scripts are not executed. OAuth/API-key helpers, multipart/file uploads,
external environment files and dynamic variables require manual setup in the replay editor.
Unresolved variables and unsupported body modes are identified in imported request notes.

Use Export to choose selected or filtered calls, session or Postman format, and
whether to include captured sensitive headers. By default, sensitive headers are redacted.
Postman exports represent redacted headers as collection variables you can fill in.
The optional field list redacts matching JSON keys recursively and URL query parameters.
Review the exact preview before downloading. Arbitrary text bodies may contain private data.
WebSocket messages are included only when the sensitive-data checkbox is enabled.
Binary or incomplete HTTP request bodies and WebSocket captures require session export.
Session imports and exports are capped at 500 calls and imports at 32 MiB.

Pin a request with Compare with… and select a second request. Comparison shows changed
lines from URL, method, status, headers, and bodies. Session imports support offline inspection.

### Editable replay

Choose Edit & replay, change method, URL, headers, or body, then Send request.
Replay executes on the desktop using its network access and TLS trust.
It uses HTTP/1.1 and shows redirects directly. Responses are retained under Imported / replayed.
Request bodies can be UTF-8 text or Base64. Request bodies are capped at 1 MiB,
response previews at 256 KiB, and network socket timeout at 25 seconds.
This operation sends a real request to the URL entered in the editor.
Authentication headers must be captured explicitly or supplied in the editor.

### HTTPS replay with an internal certificate authority

If replay reports a self-signed certificate in the chain or an unknown issuer, obtain
the trusted root/intermediate CA certificate from the API or network team as a PEM file.
Start the dashboard with that additional trust bundle.

```sh
python3 desktop/inspect.py com.example.app --ca-cert /path/to/company-ca.pem
```

This augments Python's default CA trust for desktop replay. Certificate chain, hostname,
and expiry verification remain enabled. The Android app uses its own TLS configuration.
A hostname mismatch or expired server certificate requires correcting the URL or server certificate.
Restart the dashboard after changing the CA file and retry the edited request.

For controlled testing when a CA file is unavailable, enable **Skip TLS verification for this replay**
in the replay editor. This disables certificate-chain and hostname verification for requests sent
with that checkbox enabled. Opening the editor resets the checkbox, and replay results record when
verification was disabled. This can expose credentials to an impersonated server, so use it only
for controlled test endpoints. These desktop changes work with the existing Android dependency.

### Timing breakdown

Add the timing event listener to the same client as the interceptor.

```kotlin
val builder = OkHttpClient.Builder()
    .addInterceptor(inspector.interceptor())
    .eventListenerFactory(inspector.eventListenerFactory())
```

If the client already has an event listener factory, wrap it with
`inspector.eventListenerFactory(existingFactory)` to preserve its callbacks.
The diagnostic panel lists DNS, connection, TLS, request/response, cache and cancellation
events with milliseconds from call start. Cached and reused connections omit phases that
do not occur. Redirects and retries may repeat timing events.

### Device mocks and network simulation

Open Device mocks and network simulation and click Load to view active rules.
Edit the JSON array and click Apply. Rules take effect on subsequent calls, use the
first enabled exact method/URL match, and are held in memory in the selected app process.

```json
[
  {
    "method": "GET",
    "url": "https://api.example.com/profile",
    "status": 401,
    "contentType": "application/json",
    "body": "{\"error\":\"expired token\"}",
    "headers": [["X-Test-Scenario", "expired-token"]],
    "delayMs": 1500
  },
  {
    "method": "POST",
    "url": "https://api.example.com/orders",
    "failure": "timeout"
  },
  {
    "method": "GET",
    "url": "https://api.example.com/feed",
    "bytesPerSecond": 2048
  }
]
```

A rule with status synthesizes an HTTP response. A rule without status proceeds to the real
endpoint after applying its simulation. failure accepts timeout or disconnect.
delayMs is limited to 60 seconds. bytesPerSecond throttles response reads.
Use an empty array to clear rules. Rules are validated atomically and remain active while
the dashboard is disconnected. HEAD, 204 and 304 mock responses have empty bodies.
Mocks have an X-Network-Inspector-Mock header and captures identify matched traffic rules.
Cancellation interrupts simulated delays.

### WebSocket messages

Create inspected WebSocket connections through the inspector.

```kotlin
val socket = inspector.newWebSocket(client, request, listener)
socket.send("hello")
```

Keep and use the returned socket. It forwards the application's listener callbacks and
records accepted outgoing sends and incoming text/binary messages.
The diagnostic panel shows direction, type, size, and captured content.
Message previews are bounded by the configured body size, with up to 200 retained messages
and an aggregate preview budget of twice that size per connection.
Existing direct `client.newWebSocket(...)` calls need this integration for message capture.
WebSocket captures are separate from any HTTP handshake captured by the regular interceptor.

## Verification

```sh
node desktop/tests/formats.test.cjs
python3 -m unittest discover -s desktop/tests -v
./gradlew :demo:assembleDebug :demo:assembleRelease :inspector:lintDebug
./gradlew :inspector:connectedDebugAndroidTest
node desktop/tests/browser.mjs
```

Android instrumentation requires an emulator or device. The optional browser check requires
Node.js 22+ and Chrome. Set CHROME_BINARY to the Chrome executable on Linux or Windows.
