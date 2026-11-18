package dev.localinspector;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;

final class DeviceBridge implements AutoCloseable {
    private final LocalServerSocket listener;
    private final String name;
    private final Thread worker;
    private volatile boolean closed;

    DeviceBridge(String name, NetworkInspector inspector) throws IOException {
        this.name = name;
        listener = new LocalServerSocket(name);
        worker = new Thread(() -> serve(inspector), "network-inspector");
        worker.setDaemon(true);
        worker.start();
    }

    private void serve(NetworkInspector inspector) {
        while (!closed) {
            LocalSocket peer;
            try {
                peer = listener.accept();
            } catch (IOException error) {
                if (!closed) Log.w("NetworkInspector", "Could not accept ADB connection", error);
                return;
            }
            try (LocalSocket client = peer) {
                if (closed) return;
                Credentials credentials = client.getPeerCredentials();
                // adbd uses shell on ordinary devices and root with adb root.
                if (credentials.getUid() != 2000 && credentials.getUid() != 0) continue;
                client.setSoTimeout(2500);
                String reply = dispatch(inspector, readCommand(client.getInputStream()));
                client.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
            } catch (IOException | JSONException | NumberFormatException error) {
                if (!closed) Log.d("NetworkInspector", "ADB connection ended", error);
            }
        }
    }

    private static String readCommand(InputStream stream) throws IOException {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        for (int index = 0; index < 256 * 1024; index++) {
            int value = stream.read();
            if (value == '\n') return command.toString("UTF-8");
            if (value == -1) break;
            command.write(value);
        }
        throw new IOException("Invalid inspector command");
    }

    private static String dispatch(NetworkInspector inspector, String command) throws JSONException {
        if (command.equals("rules")) return inspector.rules().toString();
        if (command.startsWith("{")) {
            try {
                org.json.JSONObject input = new org.json.JSONObject(command);
                inspector.setRules(input.getJSONArray("rules"));
                return "{}";
            } catch (IllegalArgumentException | JSONException error) {
                return new org.json.JSONObject().put("error", error.getMessage()).toString();
            }
        }
        if (command.equals("list")) return inspector.summaries().toString();
        if (command.equals("clear")) {
            inspector.clear();
            return "{}";
        }
        if (command.startsWith("call ")) {
            Object call = inspector.details(Long.parseLong(command.substring(5)));
            return call == null ? "null" : call.toString();
        }
        return "null";
    }

    @Override public void close() throws IOException {
        closed = true;
        // Wake accept before closing: an in-flight native accept can retain the bound socket.
        try (LocalSocket wakeup = new LocalSocket()) {
            wakeup.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (IOException ignored) {
            // The listener may already have stopped.
        } finally {
            listener.close();
        }
        try {
            worker.join(1000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
