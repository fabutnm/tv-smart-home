package io.github.fabutnm.homepanel;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One WebSocket session with Home Assistant: authenticate, subscribe_entities for the panel's entities, call
 * services. Runs only between start() and stop() (the panel's onStart/onStop); nothing stays connected in the
 * background. Callbacks arrive on the main thread.
 */
final class HaClient {
    interface Listener {
        void onConnected();
        void onEvent(JSONObject event);
        /** The connection dropped or could not be made; it is retried while started unless authFailed. */
        void onDisconnected(String reason, boolean authFailed);
        void onCallFailed(String message);
    }

    /** The answer to {@link #request}: the whole result message, or null when it failed or the session ended. */
    interface Response {
        void done(JSONObject result);
    }

    private static final String TAG = "HomePanel";
    private static final int SUBSCRIPTION_ID = 1;
    private static final long PING_MS = 30_000;
    private static final long[] RETRY_MS = {3_000, 10_000, 30_000};

    private final PanelConfig config;
    private final Collection<String> entityIds;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger nextId = new AtomicInteger(SUBSCRIPTION_ID + 1);
    /** The session that finished authenticating; null while connecting or disconnected. */
    private volatile WebSocket socket;
    /** The reader's socket from the moment it opens, so stop() can close it mid-handshake. */
    private volatile WebSocket current;
    private volatile boolean started;
    /** Bumped by every connect and stop: a reader from an older session posts nothing. */
    private volatile int generation;
    private int failures;
    private final java.util.Map<Integer, Response> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final Runnable ping = this::ping;
    private final Runnable reconnect = this::connect;

    HaClient(PanelConfig config, Collection<String> entityIds, Listener listener) {
        this.config = config;
        this.entityIds = entityIds;
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        failures = 0;
        connect();
    }

    void stop() {
        started = false;
        generation++;
        main.removeCallbacks(ping);
        main.removeCallbacks(reconnect);
        socket = null;
        WebSocket ws = current;
        current = null;
        if (ws != null) new Thread(ws::close, "ha-close").start();   // close() writes; not on the main thread
        failPending();
    }

    /** Every request still waiting gets null, on the main thread. */
    private void failPending() {
        for (Integer id : new java.util.ArrayList<>(pending.keySet())) {
            Response r = pending.remove(id);
            if (r != null) main.post(() -> r.done(null));
        }
    }

    boolean connected() {
        return socket != null;
    }

    private void connect() {
        if (!started) return;
        int g = ++generation;
        new Thread(() -> run(g), "ha-reader").start();
    }

    private boolean alive(int g) {
        return started && generation == g;
    }

    private void run(int g) {
        WebSocket ws = null;
        boolean authFailed = false;
        String reason;
        try {
            ws = WebSocket.connect(config.websocket(), 5_000, 75_000);
            current = ws;
            if (!alive(g)) throw new IOException("stopped");
            JSONObject hello = new JSONObject(ws.readText());
            if (!"auth_required".equals(hello.optString("type"))) throw new IOException("unexpected " + hello);
            ws.sendText(new JSONObject().put("type", "auth").put("access_token", config.token).toString());
            JSONObject auth = new JSONObject(ws.readText());
            if (!"auth_ok".equals(auth.optString("type"))) {
                authFailed = true;
                throw new IOException(auth.optString("message", "auth refused"));
            }
            ws.sendText(new JSONObject().put("id", SUBSCRIPTION_ID).put("type", "subscribe_entities")
                    .put("entity_ids", new JSONArray(entityIds)).toString());
            WebSocket open = ws;
            main.post(() -> {
                if (!alive(g)) return;
                socket = open;
                failures = 0;
                listener.onConnected();
                main.postDelayed(ping, PING_MS);
            });
            while (alive(g)) {
                JSONObject msg = new JSONObject(ws.readText());
                String type = msg.optString("type");
                if ("event".equals(type) && msg.optInt("id") == SUBSCRIPTION_ID) {
                    JSONObject event = msg.optJSONObject("event");
                    if (event != null) main.post(() -> {
                        if (alive(g)) listener.onEvent(event);
                    });
                } else if ("result".equals(type) && pending.containsKey(msg.optInt("id"))) {
                    Response r = pending.remove(msg.optInt("id"));
                    boolean ok = msg.optBoolean("success", false);
                    if (r != null) main.post(() -> r.done(ok ? msg : null));
                } else if ("result".equals(type) && !msg.optBoolean("success", true)) {
                    JSONObject err = msg.optJSONObject("error");
                    String text = err != null ? err.optString("message", "error") : "error";
                    Log.w(TAG, "call " + msg.optInt("id") + " failed: " + text);
                    main.post(() -> {
                        if (alive(g)) listener.onCallFailed(text);
                    });
                }
            }
            reason = "stopped";
        } catch (IOException | JSONException e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        if (ws != null) ws.close();
        String why = reason;
        boolean refused = authFailed;
        WebSocket mine = ws;
        main.post(() -> {
            if (!alive(g)) return;   // stop() or a newer session already took over
            if (socket == mine) socket = null;
            if (current == mine) current = null;
            main.removeCallbacks(ping);
            failPending();
            Log.w(TAG, "disconnected: " + why);
            listener.onDisconnected(why, refused);
            if (!refused) main.postDelayed(reconnect, RETRY_MS[Math.min(failures++, RETRY_MS.length - 1)]);
        });
    }

    private void ping() {
        sendAsync(new JSONObject(), "ping");
        main.postDelayed(ping, PING_MS);
    }

    /** domain.service on one entity, with optional service data. */
    void call(String domain, String service, String entityId, JSONObject data) {
        try {
            JSONObject msg = new JSONObject().put("domain", domain).put("service", service)
                    .put("target", new JSONObject().put("entity_id", entityId));
            if (data != null) msg.put("service_data", data);
            sendAsync(msg, "call_service");
        } catch (JSONException e) {
            listener.onCallFailed(e.getMessage());
        }
    }

    /** A command with an answer (get_states, the registries); the response arrives on the main thread. */
    void request(String type, Response response) {
        WebSocket ws = socket;
        if (ws == null) {
            response.done(null);
            return;
        }
        int id = nextId.getAndIncrement();
        pending.put(id, response);
        String text;
        try {
            text = new JSONObject().put("id", id).put("type", type).toString();
        } catch (JSONException e) {
            pending.remove(id);
            response.done(null);
            return;
        }
        new Thread(() -> {
            try {
                ws.sendText(text);
            } catch (IOException e) {
                Response r = pending.remove(id);
                if (r != null) main.post(() -> r.done(null));
            }
        }, "ha-request").start();
    }

    private void sendAsync(JSONObject msg, String type) {
        WebSocket ws = socket;
        if (ws == null) {
            if (!"ping".equals(type)) listener.onCallFailed(null);
            return;
        }
        try {
            msg.put("id", nextId.getAndIncrement()).put("type", type);
        } catch (JSONException e) {
            return;
        }
        String text = msg.toString();
        new Thread(() -> {
            try {
                ws.sendText(text);
            } catch (IOException e) {
                Log.w(TAG, "send failed: " + e.getMessage());
            }
        }, "ha-send").start();
    }
}
