package dev.localinspector;

import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.Okio;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class Exchange {
    final long id;
    private String method;
    private final String url;
    private final long startedAt = System.currentTimeMillis();
    private final long startedNanos = System.nanoTime();
    private final JSONArray requestHeaders;
    private final BodyCapture sent;
    private final BodyCapture received;
    private final Set<String> secrets;
    private JSONArray responseHeaders = new JSONArray();
    private String responseUrl;
    private String protocol;
    private String phase = "Waiting for response";
    private String error;
    private Integer status;
    private Long headersMs;
    private Long elapsedMs;
    private CallTiming timing;
    private boolean matchedRule;
    private final int messageLimit;
    private final java.util.ArrayDeque<JSONObject> messages = new java.util.ArrayDeque<>();
    private long messageBytes;

    synchronized void setTiming(CallTiming timing) {
        this.timing = timing;
    }

    synchronized void setRule(boolean matched) {
        matchedRule = matched;
    }

    synchronized void socketOpened(Response response) {
        method = "WS";
        status = response.code();
        responseHeaders = headers(response.headers());
        protocol = response.protocol().toString();
        headersMs = elapsed();
        phase = "WebSocket open";
    }

    synchronized void socketStarting() {
        method = "WS";
        phase = "WebSocket connecting";
    }

    synchronized void socketClosed(Throwable failure) {
        finish("none", failure);
    }

    synchronized void message(String direction, okio.ByteString value, boolean binary) {
        int size = value.size();
        int captured = Math.min(size, messageLimit);
        okio.ByteString preview = value.substring(0, captured);
        try {
            JSONObject message = new JSONObject().put("direction", direction)
                    .put("kind", binary ? "binary" : "text").put("bytes", size)
                    .put("truncated", captured < size).put("elapsedMs", elapsed())
                    .put("base64", preview.base64());
            if (!binary) message.put("text", preview.utf8());
            while (!messages.isEmpty() && (messages.size() >= 200 || messageBytes + captured > messageLimit * 2L)) {
                messageBytes -= messages.removeFirst().optInt("capturedBytes");
            }
            message.put("capturedBytes", captured);
            messages.addLast(message);
            messageBytes += captured;
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    Exchange(long id, Request request, int limit, Set<String> secrets) {
        this.id = id;
        this.messageLimit = limit;
        this.method = request.method();
        this.url = request.url().toString();
        this.secrets = secrets;
        Headers.Builder headers = request.headers().newBuilder();
        RequestBody body = request.body();
        MediaType contentType = body == null ? null : body.contentType();
        if (contentType != null) headers.set("Content-Type", contentType.toString());
        requestHeaders = headers(headers.build());
        sent = new BodyCapture(limit, contentType, body == null ? "none"
                : body.isDuplex() ? "duplex" : "waiting");
        received = new BodyCapture(limit, null, "waiting");
    }

    RequestBody captureRequest(RequestBody original) {
        return new RequestBody() {
            @Override public MediaType contentType() { return original.contentType(); }
            @Override public long contentLength() throws IOException { return original.contentLength(); }
            @Override public boolean isOneShot() { return original.isOneShot(); }

            @Override public void writeTo(BufferedSink target) throws IOException {
                sent.begin(original.contentType());
                BufferedSink tee = Okio.buffer(new ForwardingSink(target) {
                    @Override public void write(Buffer source, long byteCount) throws IOException {
                        sent.append(source, 0, byteCount);
                        super.write(source, byteCount);
                    }
                });
                try {
                    original.writeTo(tee);
                    tee.emit();
                    sent.finish("complete");
                } catch (IOException | RuntimeException error) {
                    sent.finish("partial");
                    throw error;
                }
            }
        };
    }

    synchronized Response captureResponse(Response response) {
        status = response.code();
        protocol = response.protocol().toString();
        responseUrl = response.request().url().toString();
        responseHeaders = headers(response.headers());
        headersMs = elapsed();
        ResponseBody body = response.body();
        if (body == null || body.contentLength() == 0 || response.code() == 101) {
            finish("none", null);
            return response;
        }
        phase = "Reading response";
        received.begin(body.contentType());
        ResponseBody wrapped = new ResponseBody() {
            private BufferedSource source;

            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() { return body.contentLength(); }

            @Override public synchronized BufferedSource source() {
                if (source == null) {
                    source = Okio.buffer(new ForwardingSource(body.source()) {
                        @Override public long read(Buffer sink, long byteCount) throws IOException {
                            long offset = sink.size();
                            try {
                                long count = super.read(sink, byteCount);
                                if (count == -1) finish("complete", null);
                                else received.append(sink, offset, count);
                                return count;
                            } catch (IOException | RuntimeException error) {
                                finish("partial", error);
                                throw error;
                            }
                        }

                        @Override public void close() throws IOException {
                            try {
                                super.close();
                                finish(contentLength() == received.byteCount()
                                        ? "complete" : "partial", null);
                            } catch (IOException | RuntimeException error) {
                                finish("partial", error);
                                throw error;
                            }
                        }
                    });
                }
                return source;
            }
        };
        return response.newBuilder().body(wrapped).build();
    }

    synchronized void fail(Throwable failure) {
        finish("unavailable", failure);
    }

    private synchronized void finish(String bodyState, Throwable failure) {
        if (elapsedMs != null) return;
        elapsedMs = elapsed();
        phase = failure == null ? "Finished" : "Failed";
        if (failure != null) error = failure.toString();
        received.finish(bodyState);
    }

    private long elapsed() {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    synchronized JSONObject summary() throws JSONException {
        return new JSONObject().put("id", id).put("method", method).put("url", url)
                .put("startedAt", startedAt).put("status", status).put("phase", phase)
                .put("elapsedMs", elapsedMs).put("headersMs", headersMs);
    }

    synchronized JSONObject details() throws JSONException {
        return summary().put("protocol", protocol).put("responseUrl", responseUrl)
                .put("error", error).put("requestHeaders", requestHeaders)
                .put("responseHeaders", responseHeaders)
                .put("requestBody", sent.json()).put("responseBody", received.json())
                .put("timings", timing == null ? new JSONArray() : timing.json())
                .put("messages", new JSONArray(messages))
                .put("source", matchedRule ? "Device · traffic rule applied" : "Device");
    }

    private JSONArray headers(Headers headers) {
        JSONArray result = new JSONArray();
        for (int index = 0; index < headers.size(); index++) {
            String name = headers.name(index);
            boolean redacted = secrets.contains(name.toLowerCase(Locale.ROOT));
            result.put(new JSONArray().put(name)
                    .put(redacted ? "[redacted]" : headers.value(index)).put(redacted));
        }
        return result;
    }

    private static final class BodyCapture {
        private final int limit;
        private final Buffer prefix = new Buffer();
        private long count;
        private MediaType mediaType;
        private String state;

        BodyCapture(int limit, MediaType mediaType, String state) {
            this.limit = limit;
            this.mediaType = mediaType;
            this.state = state;
        }

        synchronized void begin(MediaType type) {
            prefix.clear();
            count = 0;
            mediaType = type;
            state = "reading";
        }

        synchronized void append(Buffer source, long offset, long amount) {
            long toCopy = Math.min(amount, limit - prefix.size());
            if (toCopy > 0) source.copyTo(prefix, offset, toCopy);
            count += amount;
        }

        synchronized long byteCount() {
            return count;
        }

        synchronized void finish(String state) {
            this.state = state;
        }

        synchronized JSONObject json() throws JSONException {
            byte[] bytes = prefix.snapshot().toByteArray();
            JSONObject result = new JSONObject().put("state", state).put("bytes", count)
                    .put("capturedBytes", bytes.length).put("truncated", count > bytes.length)
                    .put("mediaType", mediaType == null ? null : mediaType.toString())
                    .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP));
            if (mediaType != null && isText(mediaType)) {
                result.put("text", new String(bytes, mediaType.charset(StandardCharsets.UTF_8)));
            }
            return result;
        }

        private static boolean isText(MediaType type) {
            String subtype = type.subtype();
            return type.type().equals("text") || subtype.equals("json") || subtype.endsWith("+json")
                    || subtype.equals("xml") || subtype.endsWith("+xml")
                    || subtype.equals("x-www-form-urlencoded") || subtype.equals("javascript");
        }
    }
}
