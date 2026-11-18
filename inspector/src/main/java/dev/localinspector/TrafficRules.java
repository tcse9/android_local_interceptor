package dev.localinspector;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable rule snapshots make edits apply only to subsequent requests. */
final class TrafficRules {
    private volatile List<JSONObject> rules = Collections.emptyList();

    void replace(JSONArray input) throws JSONException {
        if (input.length() > 30) throw new IllegalArgumentException("At most 30 rules are supported");
        List<JSONObject> validated = new ArrayList<>();
        for (int index = 0; index < input.length(); index++) {
            JSONObject rule = new JSONObject(input.getJSONObject(index).toString());
            String method = rule.getString("method");
            String url = rule.getString("url");
            if (!method.matches("[A-Z]+") || !(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new IllegalArgumentException("Each rule needs an uppercase method and an exact HTTP(S) URL");
            }
            for (String field : new String[]{"delayMs", "bytesPerSecond", "status"}) {
                if (rule.has(field) && !(rule.get(field) instanceof Integer)
                        && !(rule.get(field) instanceof Long)) {
                    throw new IllegalArgumentException(field + " must be an integer");
                }
            }
            int delay = rule.optInt("delayMs", 0);
            int bandwidth = rule.optInt("bytesPerSecond", 0);
            int status = rule.optInt("status", 200);
            if (delay < 0 || delay > 60000 || bandwidth < 0 || bandwidth > 100000000
                    || status < 200 || status > 599 || rule.optString("body", "").getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536) {
                throw new IllegalArgumentException("Rule limits: delay 0–60000 ms, body 64 KiB, status 200–599");
            }
            String failure = rule.optString("failure", "");
            if (!failure.isEmpty() && !failure.equals("timeout") && !failure.equals("disconnect")) {
                throw new IllegalArgumentException("failure must be timeout or disconnect");
            }
            JSONArray headers = rule.optJSONArray("headers");
            if (headers != null) {
                okhttp3.Headers.Builder check = new okhttp3.Headers.Builder();
                for (int item = 0; item < headers.length(); item++) {
                    JSONArray pair = headers.getJSONArray(item);
                    check.add(pair.getString(0), pair.getString(1));
                }
            }
            validated.add(rule);
        }
        rules = Collections.unmodifiableList(validated);
    }

    JSONArray json() throws JSONException {
        JSONArray result = new JSONArray();
        for (JSONObject rule : rules) result.put(new JSONObject(rule.toString()));
        return result;
    }

    JSONObject match(Request request) {
        for (JSONObject rule : rules) {
            if (rule.optBoolean("enabled", true) && rule.optString("method").equals(request.method())
                    && rule.optString("url").equals(request.url().toString())) return rule;
        }
        return null;
    }

    Response execute(Interceptor.Chain chain, Request request, JSONObject rule) throws IOException {
        if (rule == null) return chain.proceed(request);
        pause(chain, rule.optInt("delayMs", 0));
        String failure = rule.optString("failure", "");
        if (failure.equals("timeout")) throw new SocketTimeoutException("Inspector simulated timeout");
        if (failure.equals("disconnect")) throw new IOException("Inspector simulated connection failure");

        Response result;
        if (rule.has("status")) {
            // Write through the request tee once so mocked uploads are inspectable too.
            if (request.body() != null && !request.body().isDuplex()) {
                try (okio.BufferedSink sink = Okio.buffer(Okio.blackhole())) {
                    request.body().writeTo(sink);
                }
            }
            Response.Builder mock = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(rule.optInt("status", 200)).message("Inspector mock")
                    .header("X-Network-Inspector-Mock", "true");
            JSONArray headers = rule.optJSONArray("headers");
            if (headers != null) {
                for (int index = 0; index < headers.length(); index++) {
                    JSONArray pair = headers.optJSONArray(index);
                    mock.addHeader(pair.optString(0), pair.optString(1));
                }
            }
            String contentType = rule.optString("contentType", "application/json");
            // No payload is legal for HEAD, 204 and 304 responses.
            String body = request.method().equals("HEAD") || rule.optInt("status") == 204
                    || rule.optInt("status") == 304 ? "" : rule.optString("body", "");
            result = mock.header("Content-Type", contentType)
                    .body(ResponseBody.create(body, MediaType.parse(contentType))).build();
        } else {
            result = chain.proceed(request);
        }
        int bandwidth = rule.optInt("bytesPerSecond", 0);
        ResponseBody body = result.body();
        if (bandwidth == 0 || body == null) return result;
        ResponseBody throttled = new ResponseBody() {
            private final BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                @Override public long read(Buffer sink, long requested) throws IOException {
                    long count = super.read(sink, Math.min(requested, Math.max(1, bandwidth / 10)));
                    if (count > 0) pause(chain, Math.max(1, count * 1000 / bandwidth));
                    return count;
                }
            });
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }
            @Override public BufferedSource source() { return source; }
        };
        return result.newBuilder().body(throttled).build();
    }

    private static void pause(Interceptor.Chain chain, long millis) throws IOException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (chain.call().isCanceled()) throw new IOException("Canceled");
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            try {
                Thread.sleep(Math.max(1, Math.min(50, remaining)));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Inspector delay interrupted", error);
            }
        }
    }
}
