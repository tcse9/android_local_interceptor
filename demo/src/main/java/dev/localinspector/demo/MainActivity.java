package dev.localinspector.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class MainActivity extends Activity {
    private OkHttpClient client;
    private EditText url;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        client = DemoClient.get(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding * 2, padding, padding);

        TextView title = new TextView(this);
        title.setText("Network Inspector Demo");
        title.setTextSize(24);
        layout.addView(title);

        url = new EditText(this);
        url.setSingleLine(true);
        url.setText("https://httpbin.org/anything");
        url.setHint("Request URL");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(url);
        for (String method : new String[]{"GET", "POST"}) {
            Button button = new Button(this);
            button.setText("Send " + method);
            button.setOnClickListener(view -> send(method));
            layout.addView(button);
        }
        status = new TextView(this);
        status.setText("Choose a request to send.");
        layout.addView(status);
        setContentView(layout);
    }

    private void send(String method) {
        RequestBody body = method.equals("POST")
                ? RequestBody.create("{\"hello\":\"Android\"}", MediaType.get("application/json"))
                : null;
        Request request;
        try {
            request = new Request.Builder().url(url.getText().toString()).method(method, body).build();
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        status.setText("Sending " + method + "…");
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                runOnUiThread(() -> status.setText(error.toString()));
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response result = response) {
                    long bytes = 0;
                    if (result.body() != null) {
                        okio.Buffer buffer = new okio.Buffer();
                        long count;
                        while ((count = result.body().source().read(buffer, 8192)) != -1) {
                            bytes += count;
                            buffer.clear();
                        }
                    }
                    String summary = "HTTP " + result.code() + " · " + bytes + " bytes";
                    runOnUiThread(() -> status.setText(summary));
                } catch (IOException error) {
                    onFailure(call, error);
                }
            }
        });
    }
}
