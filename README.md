# Android Local Network Inspector

A debug-only Android library and a local browser dashboard. Inspect OkHttp calls as they happen, read request and response headers and bodies, and copy a cURL command. The dashboard connects over ADB and runs on your computer. HTTPS is captured inside the app, so it uses the app's existing trust and certificate-pinning configuration.

## Run the demo

Install JDK 17, Android SDK Platform 35, Android SDK Platform-Tools, and Python 3.9 or newer. Set `ANDROID_HOME` or open the project in Android Studio. Start an emulator or connect a device with USB debugging enabled.

```sh
./gradlew :demo:installDebug
adb shell am start -n dev.localinspector.demo/.MainActivity
python3 desktop/inspect.py dev.localinspector.demo
```

Tap **Send GET** or **Send POST** in the demo. Its URL field defaults to a public HTTPS echo endpoint and accepts your own HTTPS endpoint. To try it offline, run `python3 -m http.server --bind 127.0.0.1 8000` from a directory you want to serve, enter `http://10.0.2.2:8000/` in the Android emulator, and tap **Send GET**. The dashboard opens automatically. If you have multiple devices attached, pass `--serial SERIAL` to the dashboard and use the same device for installation and launch. Use `--no-open` to print the URL only or `--adb /path/to/adb` if ADB is outside your PATH.

## Add it to your app

Publish the AAR and its dependency metadata to your local Maven repository.

```sh
./gradlew :inspector:publishToMavenLocal
```

In the consuming project, add `mavenLocal()` under `dependencyResolutionManagement.repositories` in `settings.gradle.kts`, alongside your existing repositories. In the app's `build.gradle.kts`, add:

```kotlin
dependencies {
    debugImplementation("io.github.tcse9:inspector:0.1.0")
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

- Each configured client produces one record per logical OkHttp call, including cache hits and failed requests. Redirects and retries are grouped into the original call, and the dashboard shows the final response URL when it differs. Calls made through WebView, Cronet, HttpURLConnection, native sockets, or other uninstrumented clients are outside this capture path. WebSocket handshakes are visible. WebSocket frames and duplex request bodies are outside body capture.
- Response content appears as the app reads it. Early closure is marked partial when the full body cannot be confirmed. Long-lived responses stay readable without waiting for them to finish. The dashboard displays recognized text types and shows Base64 for other types.
- The default buffer holds the latest 150 calls and up to 64 KiB per request and response body. The constructor accepts custom limits and extra header names to redact. Bodies and query parameters may contain sensitive values. Captures stay in memory and are cleared with **Clear** or when the app process ends. Pausing the dashboard pauses its live updates while Android continues capturing.
- Authorization, Proxy-Authorization, Cookie, Set-Cookie, and X-API-Key are redacted. cURL omits redacted and transport-managed headers. For commands that include body bytes, replay requires a POSIX shell, cURL, and OpenSSL. Export is available when the request has no body or its entire body was captured.

## Publish to Maven Central

Follow [PUBLISHING.md](PUBLISHING.md) to prepare a signed release bundle and publish it through the Central Portal.
