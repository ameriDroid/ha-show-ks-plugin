// SPDX-License-Identifier: Apache-2.0
// Runs the plugin on a desktop JVM against the real service API (adb forward tcp:8765 tcp:8765) with a recording fake host.
import com.ameridroid.hashow.ks.ManagerPlugin;
import me.jxl.kiosk.plugins.PluginHost;

import java.lang.reflect.Proxy;
import java.util.*;

public class ManagerPluginHarness {
    static final List<String> log = new ArrayList<>();
    static Map<String, Object> lastState;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        PluginHost host = (PluginHost) Proxy.newProxyInstance(PluginHost.class.getClassLoader(), new Class<?>[]{PluginHost.class},
            (proxy, m, a) -> {
                if (m.getName().equals("publishLight")) {
                    lastState = (Map<String, Object>) a[3];
                    log.add("publishLight " + a[1] + " effects=" + Arrays.toString((String[]) a[2]) + " state=" + lastState);
                } else if (m.getName().equals("status")) {
                    log.add("status(" + a[1] + ") " + a[0]);
                }
                return null;
            });
        ManagerPlugin p = new ManagerPlugin();
        p.start(host, new HashMap<>());
        step("initial publish", p, null);

        step("HA: effect Rainbow, brightness 0.4", p, map("on", true, "effect", "Rainbow", "brightness", 0.4));
        step("HA: effect Comet", p, map("on", true, "effect", "Comet"));
        step("HA: colour blue (effect None)", p, map("on", true, "effect", "None", "red", 0.0, "green", 0.4, "blue", 1.0));
        step("HA: off", p, map("on", false));
        step("HA: on (restores last effect)", p, map("on", true));
        step("HA: off again", p, map("on", false));
        p.stop();
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static void step(String what, ManagerPlugin p, Map<String, Object> payload) throws Exception {
        if (payload != null) p.onEvent("light.strip", payload);
        System.out.println("== " + what);
        System.out.println("   state: " + lastState);
        Thread.sleep(2500);
    }
}
