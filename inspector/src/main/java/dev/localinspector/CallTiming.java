package dev.localinspector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class CallTiming {
    private final long start = System.nanoTime();
    private final List<String> names = new ArrayList<>();
    private final List<Long> times = new ArrayList<>();

    synchronized void mark(String name) {
        if (names.size() >= 500) return;
        names.add(name);
        times.add((System.nanoTime() - start) / 1_000_000);
    }

    synchronized JSONArray json() throws JSONException {
        JSONArray result = new JSONArray();
        for (int index = 0; index < names.size(); index++) {
            result.put(new JSONObject().put("name", names.get(index)).put("elapsedMs", times.get(index)));
        }
        return result;
    }
}
