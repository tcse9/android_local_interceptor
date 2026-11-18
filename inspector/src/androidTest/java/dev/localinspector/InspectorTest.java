package dev.localinspector;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class InspectorTest {
    private void start(NetworkInspector inspector) throws Exception {
        inspector.start(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test public void sensitiveHeadersRespectCaptureAndExplicitRedaction() throws Exception {
        for (boolean include : new boolean[]{false, true}) {
            try (NetworkInspector inspector = new NetworkInspector(include)) {
                start(inspector);
                inspector.setRules(new JSONArray("[{\"method\":\"GET\",\"url\":\"http://localhost/example\",\"status\":200}]"));
                OkHttpClient client = new OkHttpClient.Builder().addInterceptor(inspector.interceptor()).build();
                try (Response response = client.newCall(new Request.Builder().url("http://localhost/example")
                        .header("Authorization", "Bearer auth").header("Proxy-Authorization", "Bearer proxy").build()).execute()) {
                    response.body().string();
                }
                JSONArray headers = inspector.details(1).getJSONArray("requestHeaders");
                assertEquals(include ? "Bearer auth" : "[redacted]", headers.getJSONArray(0).getString(1));
                assertEquals(include ? "Bearer proxy" : "[redacted]", headers.getJSONArray(1).getString(1));
            }
        }
        try (NetworkInspector inspector = new NetworkInspector(10, 4096, Arrays.asList("Authorization"), true)) {
            start(inspector);
            inspector.setRules(new JSONArray("[{\"method\":\"GET\",\"url\":\"http://localhost/example\",\"status\":200}]"));
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(inspector.interceptor()).build();
            try (Response response = client.newCall(new Request.Builder().url("http://localhost/example")
                    .header("Authorization", "Bearer secret").build()).execute()) {
                response.body().string();
            }
            assertEquals("[redacted]", inspector.details(1).getJSONArray("requestHeaders").getJSONArray(0).getString(1));
        }
    }

    @Test public void rulesAreAtomicAndMockedUploadsRemainReadable() throws Exception {
        try (NetworkInspector inspector = new NetworkInspector()) {
            start(inspector);
            String rules = "[{\"method\":\"POST\",\"url\":\"http://localhost/mock\",\"status\":201,"
                    + "\"delayMs\":50,\"body\":\"{\\\"ok\\\":true}\",\"contentType\":\"application/json\"}]";
            inspector.setRules(new JSONArray(rules));
            try {
                inspector.setRules(new JSONArray("[{\"method\":\"POST\",\"url\":\"http://localhost/mock\",\"delayMs\":-1}]"));
                fail("Invalid rules should fail atomically");
            } catch (IllegalArgumentException expected) {
                assertEquals(201, inspector.rules().getJSONObject(0).getInt("status"));
            }
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(inspector.interceptor()).build();
            long start = System.nanoTime();
            try (Response response = client.newCall(new Request.Builder().url("http://localhost/mock")
                    .post(RequestBody.create("{\"upload\":true}", MediaType.get("application/json"))).build()).execute()) {
                assertEquals(201, response.code());
                assertEquals("{\"ok\":true}", response.body().string());
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 45);
            JSONObject call = inspector.details(1);
            assertEquals("{\"upload\":true}", call.getJSONObject("requestBody").getString("text"));
            assertEquals("complete", call.getJSONObject("requestBody").getString("state"));
            assertEquals("Finished", call.getString("phase"));
            inspector.setRules(new JSONArray("[{\"method\":\"GET\",\"url\":\"http://localhost/fail\",\"failure\":\"timeout\"}]"));
            try {
                client.newCall(new Request.Builder().url("http://localhost/fail").build()).execute();
                fail("Expected simulated timeout");
            } catch (java.net.SocketTimeoutException expected) {
                assertEquals("Failed", inspector.details(2).getString("phase"));
            }
        }
    }

    @Test public void timingPreservesDelegateAndResponseBytes() throws Exception {
        try (MockWebServer server = new MockWebServer(); NetworkInspector inspector = new NetworkInspector()) {
            start(inspector);
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));
            AtomicInteger callbacks = new AtomicInteger();
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(inspector.interceptor())
                    .eventListenerFactory(inspector.eventListenerFactory(call -> new EventListener() {
                        @Override public void callStart(Call call) { callbacks.incrementAndGet(); }
                        @Override public void callEnd(Call call) { callbacks.incrementAndGet(); }
                    })).build();
            try (Response response = client.newCall(new Request.Builder().url(server.url("/timing")).build()).execute()) {
                assertEquals("{\"ok\":true}", response.body().string());
            }
            assertEquals(2, callbacks.get());
            JSONObject call = inspector.details(1);
            String events = call.getJSONArray("timings").toString();
            assertTrue(events.contains("connectStart"));
            assertTrue(events.contains("requestHeadersEnd"));
            assertTrue(events.contains("callEnd"));
            assertEquals("{\"ok\":true}", call.getJSONObject("responseBody").getString("text"));
        }
    }

    @Test public void simulationThrottlesReadsAndHonorsCancellation() throws Exception {
        try (NetworkInspector inspector = new NetworkInspector()) {
            start(inspector);
            inspector.setRules(new JSONArray("[{\"method\":\"GET\",\"url\":\"http://localhost/slow\","
                    + "\"status\":200,\"bytesPerSecond\":320,\"body\":\"12345678901234567890123456789012\"},"
                    + "{\"method\":\"GET\",\"url\":\"http://localhost/delay\",\"status\":200,\"delayMs\":5000}]"));
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(inspector.interceptor()).build();
            long started = System.nanoTime();
            try (Response response = client.newCall(new Request.Builder().url("http://localhost/slow").build()).execute()) {
                assertEquals(32, response.body().string().length());
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 90);
            Call delayed = client.newCall(new Request.Builder().url("http://localhost/delay").build());
            CountDownLatch completed = new CountDownLatch(1);
            delayed.enqueue(new okhttp3.Callback() {
                @Override public void onFailure(Call call, java.io.IOException error) {
                    completed.countDown();
                }
                @Override public void onResponse(Call call, Response response) {
                    response.close();
                }
            });
            Thread.sleep(100);
            delayed.cancel();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    @Test public void webSocketCapturesTextAndBinaryBothDirections() throws Exception {
        try (MockWebServer server = new MockWebServer(); NetworkInspector inspector = new NetworkInspector()) {
            start(inspector);
            CountDownLatch received = new CountDownLatch(2);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onMessage(WebSocket socket, String value) { socket.send(value); }
                @Override public void onMessage(WebSocket socket, ByteString value) { socket.send(value); }
            }));
            OkHttpClient client = new OkHttpClient.Builder().build();
            WebSocket socket = inspector.newWebSocket(client, new Request.Builder().url(server.url("/ws")).build(),
                    new WebSocketListener() {
                        @Override public void onMessage(WebSocket socket, String value) { received.countDown(); }
                        @Override public void onMessage(WebSocket socket, ByteString value) { received.countDown(); }
                    });
            assertTrue(socket.send("hello"));
            assertTrue(socket.send(ByteString.decodeHex("00ff")));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            JSONObject call = inspector.details(1);
            assertEquals("WS", call.getString("method"));
            assertEquals(4, call.getJSONArray("messages").length());
            socket.cancel();
            assertEquals("Failed", inspector.details(1).getString("phase"));
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
