package dev.localinspector;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

final class InspectedWebSocket implements WebSocket {
    private volatile WebSocket delegate;
    private final Exchange exchange;

    private InspectedWebSocket(Exchange exchange) {
        this.exchange = exchange;
    }

    static WebSocket open(OkHttpClient client, Request request, WebSocketListener listener, Exchange exchange) {
        InspectedWebSocket socket = new InspectedWebSocket(exchange);
        exchange.socketStarting();
        socket.delegate = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket connection, Response response) {
                socket.delegate = connection;
                exchange.socketOpened(response);
                listener.onOpen(socket, response);
            }

            @Override public void onMessage(WebSocket connection, String value) {
                exchange.message("Received", ByteString.encodeUtf8(value), false);
                listener.onMessage(socket, value);
            }

            @Override public void onMessage(WebSocket connection, ByteString value) {
                exchange.message("Received", value, true);
                listener.onMessage(socket, value);
            }

            @Override public void onClosing(WebSocket connection, int code, String reason) {
                listener.onClosing(socket, code, reason);
            }

            @Override public void onClosed(WebSocket connection, int code, String reason) {
                exchange.socketClosed(null);
                listener.onClosed(socket, code, reason);
            }

            @Override public void onFailure(WebSocket connection, Throwable error, Response response) {
                socket.delegate = connection;
                exchange.socketClosed(error);
                listener.onFailure(socket, error, response);
            }
        });
        return socket;
    }

    @Override public Request request() { return delegate.request(); }
    @Override public long queueSize() { return delegate.queueSize(); }

    @Override public boolean send(String value) {
        boolean accepted = delegate.send(value);
        if (accepted) exchange.message("Sent", ByteString.encodeUtf8(value), false);
        return accepted;
    }

    @Override public boolean send(ByteString value) {
        boolean accepted = delegate.send(value);
        if (accepted) exchange.message("Sent", value, true);
        return accepted;
    }

    @Override public boolean close(int code, String reason) { return delegate.close(code, reason); }
    @Override public void cancel() { delegate.cancel(); exchange.socketClosed(new java.io.IOException("Canceled")); }
}
