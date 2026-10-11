# HA Show Manager plugin for Kiosk Satellite

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that connects the ameriDroid HA Show to Home Assistant through the
HA Show manager's local API (127.0.0.1:8765, see [ha-show-manager](https://github.com/ameriDroid/ha-show-manager)):

- the LED strip as an RGB light with effects and voice patterns, microphone mute,
- system, manager and mic-array versions, with update checks,
- the modular I2C sensors, detected automatically and published as Home Assistant sensors,
- a shortcut that opens the manager app.

## Install

Build and install over Kiosk Satellite's remote admin API (it must be enabled, port 2324):

```sh
export JAVA_HOME=...   ANDROID_HOME=...     # JDK 17 and an Android SDK with build-tools
./install_plugin.sh <kiosk-ip> <remote-admin-password>
```

or download the plugin zip from a release and install it in Kiosk Satellite's plugin manager.

## Layout

`kiosk-satellite-plugin.json` (manifest, settings, commands), `src/` (the plugin, Java 8), `tests/`, `sdk/` and `tools/` (the Kiosk Satellite
plugin SDK and build tools, from [kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world),
Apache-2.0, see `LICENSE.kiosk-satellite-sdk`).
