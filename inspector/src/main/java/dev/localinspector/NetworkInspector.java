package dev.localinspector;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An in-memory inspector for OkHttp clients in a debuggable application. */
public final class NetworkInspector implements AutoCloseable {
    private final int maxCalls;
    private final int maxBodyBytes;
    private final Set<String> redactedHeaders;
    private final ArrayDeque<Exchange> calls = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();
    private volatile DeviceBridge bridge;
    private final TrafficRules trafficRules = new TrafficRules();
    private final java.util.Map<okhttp3.Call, CallTiming> timings = new java.util.WeakHashMap<>();

    /** Attach to the client to capture DNS, connection, TLS, and transfer event timestamps. */
    public okhttp3.EventListener.Factory eventListenerFactory() {
        return eventListenerFactory(call -> okhttp3.EventListener.NONE);
    }

    /** Wrap an existing factory to preserve its event callbacks. */
    public okhttp3.EventListener.Factory eventListenerFactory(okhttp3.EventListener.Factory delegate) {
        return call -> {
            CallTiming timing = new CallTiming();
            synchronized (timings) {
                timings.put(call, timing);
            }
            return new TimingListener(delegate.create(call), timing);
        };
    }

    /** Use this entry point to inspect sent and received WebSocket messages. */
    public okhttp3.WebSocket newWebSocket(okhttp3.OkHttpClient client, Request request,
            okhttp3.WebSocketListener listener) {
        Exchange exchange = begin(request);
        if (exchange == null) return client.newWebSocket(request, listener);
        return InspectedWebSocket.open(client, request, listener, exchange);
    }

    JSONArray rules() throws JSONException {
        return trafficRules.json();
    }

    void setRules(JSONArray rules) throws JSONException {
        trafficRules.replace(rules);
    }

    public NetworkInspector() {
        this(150, 64 * 1024, java.util.Collections.emptySet());
    }

    /** Captures Authorization and Proxy-Authorization values when true, including them in dashboard and cURL exports. */
    public NetworkInspector(boolean captureAuthorization) {
        this(150, 64 * 1024, java.util.Collections.emptySet(), captureAuthorization);
    }

    public NetworkInspector(int maxCalls, int maxBodyBytes, Collection<String> extraRedactedHeaders) {
        this(maxCalls, maxBodyBytes, extraRedactedHeaders, false);
    }

    public NetworkInspector(int maxCalls, int maxBodyBytes, Collection<String> extraRedactedHeaders,
            boolean captureAuthorization) {
        if (maxCalls < 1 || maxCalls > 500 || maxBodyBytes < 1 || maxBodyBytes > 256 * 1024
                || (long) maxCalls * maxBodyBytes > 32L * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid capture limits");
        }
        this.maxCalls = maxCalls;
        this.maxBodyBytes = maxBodyBytes;
        redactedHeaders = new HashSet<>(java.util.Arrays.asList(
                "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key"));
        if (captureAuthorization) {
            redactedHeaders.remove("authorization");
            redactedHeaders.remove("proxy-authorization");
        }
        for (String header : extraRedactedHeaders) {
            redactedHeaders.add(header.toLowerCase(Locale.ROOT));
        }
    }

    /** Starts capture and exposes the app's debug socket to ADB. */
    public synchronized void start(Context context) throws IOException {
        if (bridge != null) return;
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            throw new IllegalStateException("Network Inspector requires a debuggable app");
        }
        bridge = new DeviceBridge(context.getPackageName() + ".network-inspector", this);
    }

    /** Add this interceptor once to each client you want to inspect. */
    public Interceptor interceptor() {
        return chain -> {
            Request request = chain.request();
            Exchange exchange = begin(request);
            if (exchange == null) return chain.proceed(request);

            synchronized (timings) {
                exchange.setTiming(timings.get(chain.call()));
            }
            JSONObject rule = trafficRules.match(request);
            exchange.setRule(rule != null);

            RequestBody body = request.body();
            if (body != null && !body.isDuplex()) {
                request = request.newBuilder()
                        .method(request.method(), exchange.captureRequest(body)).build();
            }
            try {
                Response response = trafficRules.execute(chain, request, rule);
                return exchange.captureResponse(response);
            } catch (IOException | RuntimeException error) {
                exchange.fail(error);
                throw error;
            }
        };
    }

    private synchronized Exchange begin(Request request) {
        if (bridge == null) return null;
        Exchange exchange = new Exchange(nextId.incrementAndGet(), request, maxBodyBytes, redactedHeaders);
        if (calls.size() == maxCalls) calls.removeLast();
        calls.addFirst(exchange);
        return exchange;
    }

    @Override public synchronized void close() throws IOException {
        DeviceBridge running = bridge;
        bridge = null;
        calls.clear();
        if (running != null) running.close();
    }

    synchronized void clear() {
        calls.clear();
    }

    JSONArray summaries() throws JSONException {
        Exchange[] snapshot;
        synchronized (this) {
            snapshot = calls.toArray(new Exchange[0]);
        }
        JSONArray result = new JSONArray();
        for (Exchange call : snapshot) result.put(call.summary());
        return result;
    }

    JSONObject details(long id) throws JSONException {
        Exchange match = null;
        synchronized (this) {
            for (Exchange call : calls) {
                if (call.id == id) {
                    match = call;
                    break;
                }
            }
        }
        return match == null ? null : match.details();
    }
}
