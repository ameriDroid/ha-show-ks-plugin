// SPDX-License-Identifier: Apache-2.0
package com.ameridroid.hashow.ks;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bridge between Kiosk Satellite and the HA Show manager service app (com.ameridroid.hashow.service), which listens on
 * 127.0.0.1:8765. The service owns the hardware (LED strip, XMOS mic array, light sensor) and the version checks; this
 * plugin publishes them to Home Assistant, forwards the voice assistant state to the LED strip and exposes shortcuts.
 */
public class ManagerPlugin implements KioskPlugin {
    private static final String LIGHT = "strip";
    private static final String HOST = "localhost";
    private static final int PORT = 8765;
    private static final String NONE = "None";

    /** Home Assistant effect label to service effect id. */
    private static final Map<String, String> EFFECTS = new LinkedHashMap<>();
    static {
        EFFECTS.put("Rainbow", "rainbow");
        EFFECTS.put("Colour roll", "color_roll");
        EFFECTS.put("Breathing", "breathing");
        EFFECTS.put("Comet", "comet");
        EFFECTS.put("Twinkle", "twinkle");
    }

    private volatile PluginHost host;
    private volatile boolean running;
    private Thread poller;
    private String lastEffectId = "rainbow";                 // restored when the light is turned on without an effect
    private final Set<String> sensorKeys = new HashSet<>();      // modular sensor entities currently published
    private final Map<String, String> published = new HashMap<>();   // last published value per entity, to publish changes only

    // ---- lifecycle ----

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        running = true;
        pushConfig(settings);
        try {
            host.subscribe("voice.state");
            host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    if (ok && data instanceof Map) forwardVoice(String.valueOf(((Map<?, ?>) data).get("state")));
                }
            });
        } catch (RuntimeException e) {
            host.log("voice state unavailable: " + e.getMessage());
        }
        sync();
        poller = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    sync();
                }
            }
        }, "ha-show-manager");
        poller.setDaemon(true);
        poller.start();
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        pushConfig(settings);
        sync();
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        try {
            switch (command) {
                case "openManager": post("/open", new JSONObject()); break;
                case "checkUpdates": runInBackground(new Runnable() { @Override public void run() {
                    try { requestTimeout("POST", "/check", "{}", 15000); } catch (Exception ignored) { }
                    sync();
                } }); break;
                default: break;
            }
        } catch (Exception e) {
            host.status("Manager service unavailable: " + e.getMessage(), true);
        }
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        try {
            if (event.equals("ks.voice.state")) {
                forwardVoice(String.valueOf(payload.get("state")));
            } else if (event.equals("light." + LIGHT)) {
                applyLight(payload);
                sync();
            } else if (event.equals("switch.mic_mute")) {
                post("/mic", new JSONObject().put("mute", Boolean.TRUE.equals(payload.get("on"))));
                sync();
            }
        } catch (Exception e) {
            host.status("Manager service unavailable: " + e.getMessage(), true);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (poller != null) poller.interrupt();
    }

    // ---- settings and voice ----

    private void pushConfig(Map<String, Object> settings) {
        try {
            JSONObject c = new JSONObject();
            if (settings.get("statusPatterns") instanceof Boolean) c.put("status_patterns", settings.get("statusPatterns"));
            if (settings.get("muteIndicator") instanceof Boolean) c.put("mute_indicator", settings.get("muteIndicator"));
            if (settings.get("muteSound") instanceof Boolean) c.put("mute_sound", settings.get("muteSound"));
            if (settings.get("muteSoundVolume") instanceof Number) c.put("mute_sound_volume", ((Number) settings.get("muteSoundVolume")).intValue());
            if (settings.get("micSwitchFollow") instanceof Boolean) c.put("mic_switch_follow", settings.get("micSwitchFollow"));
            if (settings.get("micSwitchInvert") instanceof Boolean) c.put("mic_switch_invert", settings.get("micSwitchInvert"));
            if (settings.get("ledCount") instanceof Number) c.put("led_count", ((Number) settings.get("ledCount")).intValue());
            if (settings.get("updateChannel") instanceof String) c.put("update_channel", ((String) settings.get("updateChannel")).toLowerCase());
            if (settings.get("autoCheck") instanceof Boolean) c.put("auto_check", settings.get("autoCheck"));
            if (c.length() > 0) post("/config", c);
        } catch (Exception e) {
            host.log("config not applied: " + e.getMessage());
        }
    }

    private void forwardVoice(String state) {
        try { post("/voice", new JSONObject().put("state", state)); } catch (Exception ignored) { }
    }

    // ---- LED light: Home Assistant -> service ----

    private void applyLight(Map<String, Object> payload) throws Exception {
        JSONObject body = new JSONObject();
        Object on = payload.get("on");
        Object effect = payload.get("effect");
        Object brightness = payload.get("brightness");
        JSONObject current = get("/led");
        String curEffect = current.optString("effect", "off");

        if (Boolean.FALSE.equals(on)) {
            body.put("effect", "off");
        } else {
            String id = null;
            if (effect instanceof String && EFFECTS.containsKey(effect)) id = EFFECTS.get(effect);
            else if (effect instanceof String && NONE.equals(effect)) id = "solid";
            else if (Boolean.TRUE.equals(on) && curEffect.equals("off")) id = lastEffectId;
            else if (hasColor(payload) && (curEffect.equals("off") || curEffect.equals("rainbow") || curEffect.equals("color_roll"))) id = "solid";
            if (id != null) body.put("effect", id);
        }
        if (brightness instanceof Number) body.put("brightness", (int) Math.round(((Number) brightness).doubleValue() * 100));
        if (hasColor(payload)) {
            int rgb = Integer.parseInt(current.optString("color", "f26522"), 16);
            body.put("color", String.format("%02x%02x%02x",
                    channel(payload.get("red"), (rgb >> 16) & 0xFF), channel(payload.get("green"), (rgb >> 8) & 0xFF), channel(payload.get("blue"), rgb & 0xFF)));
        }
        post("/led", body);
    }

    private static boolean hasColor(Map<String, Object> p) { return p.containsKey("red") || p.containsKey("green") || p.containsKey("blue"); }

    private static int channel(Object v, int fallback) { return v instanceof Number ? (int) Math.round(((Number) v).doubleValue() * 255) : fallback; }

    // ---- service state -> Home Assistant ----

    /** Pull everything from the service and publish what changed. */
    private synchronized void sync() {
        PluginHost h = host;
        if (h == null || !running) return;
        try {
            JSONObject s = get("/status");
            JSONObject led = s.optJSONObject("led");
            JSONObject mic = s.optJSONObject("mic");
            JSONObject ver = s.optJSONObject("versions");
            JSONArray sensors = s.optJSONArray("sensors");

            if (led != null) publishLight(h, led);
            if (mic != null && !mic.isNull("muted")) {
                boolean muted = mic.optBoolean("muted");
                if (changed("mic", String.valueOf(muted))) h.publishSwitch("mic_mute", "Microphone mute", muted);
            }
            if (ver != null) {
                String hashow = ver.optString("hashow_version", "dev");
                if (changed("hashow", hashow)) h.publishTextSensor("hashow_version", "HA Show version", hashow);
                String xmos = ver.isNull("xmos_version") ? null : ver.optString("xmos_version");
                if (xmos == null && mic != null && !mic.optBoolean("present", false)) xmos = "Not connected";
                if (xmos != null && changed("xmos", xmos)) h.publishTextSensor("xmos_version", "Mic array firmware", xmos);
                String summary = ver.optString("summary", "");
                if (changed("updates", summary)) h.publishTextSensor("updates", "Updates", summary);
                boolean avail = ver.optBoolean("xmos_update_available") || ver.optBoolean("firmware_update_available");
                if (changed("avail", String.valueOf(avail))) h.publishBinarySensor("update_available", "Update available", "update", avail);
            }
            if (sensors != null) publishSensors(h, sensors);
            String line = "Connected";
            if (ver != null) line += " · HA Show " + ver.optString("hashow_version", "?") + " · mic array " + (ver.isNull("xmos_version") ? "?" : ver.optString("xmos_version"))
                    + " · " + ver.optString("summary", "");
            h.status(line, false);
        } catch (Exception e) {
            published.clear();
            h.status("HA Show manager unavailable: " + e.getMessage(), true);
        }
    }

    /** Modular I2C sensors (hashow-sensord): one Home Assistant sensor per measured value; unplugged ones are removed. */
    private void publishSensors(PluginHost h, JSONArray arr) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length() && i < 24; i++) {          // a plugin session may own 32 entities in total
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            double v = o.optDouble("value", Double.NaN);
            if (id.isEmpty() || Double.isNaN(v)) continue;
            seen.add(id);
            int decimals = Math.max(0, Math.min(6, o.optInt("decimals", 1)));
            if (changed("sensor." + id, String.format(Locale.ROOT, "%." + decimals + "f", v))) {
                Map<String, Object> meta = new LinkedHashMap<>();
                if (!o.optString("unit").isEmpty()) meta.put("unit", o.optString("unit"));                 // colour counts etc. have none
                if (!o.optString("device_class").isEmpty()) meta.put("deviceClass", o.optString("device_class"));
                meta.put("stateClass", "measurement");
                meta.put("accuracyDecimals", decimals);
                h.publishSensor(id, o.optString("name", id), meta, v);
            }
        }
        for (String old : new ArrayList<>(sensorKeys)) {
            if (!seen.contains(old)) {
                h.removeSensor(old);
                sensorKeys.remove(old);
                published.remove("sensor." + old);
            }
        }
        sensorKeys.addAll(seen);
    }

    private boolean changed(String key, String value) {
        if (value.equals(published.get(key))) return false;
        published.put(key, value);
        return true;
    }

    private void publishLight(PluginHost h, JSONObject s) {
        String id = s.optString("effect", "off");
        if (!id.equals("off") && !id.equals("solid")) lastEffectId = id;
        int rgb = Integer.parseInt(s.optString("color", "f26522"), 16);

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("on", !id.equals("off"));
        state.put("brightness", s.optInt("brightness", 30) / 100.0);
        state.put("red", ((rgb >> 16) & 0xFF) / 255.0);
        state.put("green", ((rgb >> 8) & 0xFF) / 255.0);
        state.put("blue", (rgb & 0xFF) / 255.0);
        state.put("effect", effectLabel(id));
        if (changed("light", state.toString())) h.publishLight(LIGHT, "LED strip", EFFECTS.keySet().toArray(new String[0]), state);
    }

    private static String effectLabel(String id) {
        for (Map.Entry<String, String> e : EFFECTS.entrySet()) if (e.getValue().equals(id)) return e.getKey();
        return NONE;
    }

    private static void runInBackground(Runnable r) {
        Thread t = new Thread(r, "ha-show-manager-task");
        t.setDaemon(true);
        t.start();
    }

    // ---- minimal HTTP to the service app ----

    private static JSONObject get(String path) throws Exception { return new JSONObject(request("GET", path, null)); }

    private static JSONObject post(String path, JSONObject body) throws Exception { return new JSONObject(request("POST", path, body.toString())); }

    private static String request(String method, String path, String body) throws Exception { return requestTimeout(method, path, body, 1500); }

    private static String requestTimeout(String method, String path, String body, int readMillis) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(HOST, PORT), 1000);
            s.setSoTimeout(readMillis);
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: " + HOST + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            String resp = new String(all.toByteArray(), StandardCharsets.UTF_8);
            int split = resp.indexOf("\r\n\r\n");
            if (!resp.startsWith("HTTP/1.1 200") || split < 0) throw new Exception("bad response");
            return resp.substring(split + 4);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }
}
