package dev.localinspector.demo;

import android.app.Activity;
import okhttp3.OkHttpClient;

final class DemoClient {
    private static final OkHttpClient CLIENT = new OkHttpClient();

    static OkHttpClient get(Activity activity) {
        return CLIENT;
    }
}
