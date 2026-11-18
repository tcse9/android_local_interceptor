package dev.localinspector.demo;

import android.app.Activity;
import android.util.Log;
import dev.localinspector.NetworkInspector;
import java.io.IOException;
import okhttp3.OkHttpClient;

final class DemoClient {
    private static final NetworkInspector INSPECTOR = new NetworkInspector();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .addInterceptor(INSPECTOR.interceptor())
            .eventListenerFactory(INSPECTOR.eventListenerFactory()).build();
    private static boolean started;

    static synchronized OkHttpClient get(Activity activity) {
        if (!started) {
            try {
                INSPECTOR.start(activity);
                started = true;
            } catch (IOException error) {
                Log.e("NetworkInspector", "Could not start inspector", error);
            }
        }
        return CLIENT;
    }
}
