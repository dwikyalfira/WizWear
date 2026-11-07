# WiZ Lights for Wear OS

Control Philips **WiZ** bulbs from a Wear OS watch over your **local Wi-Fi** — no
internet, no WiZ cloud, no Google Home. The watch talks to the bulbs directly with
WiZ's local UDP protocol (port `38899`).

Built with Kotlin, Jetpack Compose for Wear OS (Material 3), ProtoLayout Material 3
tiles, and a watch-face complication.

## Features

- **Auto discovery** — broadcast `getPilot`, falling back to a /24 sweep when the
  router drops broadcasts. Bulbs are tracked by MAC, so a new DHCP lease is found
  again automatically.
- **Knows each bulb's capabilities** (`getSystemConfig` / `getModelConfig`):
  colour controls only appear on RGB bulbs, the scene list only shows scenes the bulb
  can play, and the colour-temperature range follows the bulb (e.g. 2700–6500 K).
- **App** (Wear Compose Material 3)
  - Home: one card per bulb with an on/off switch; *Find lights* EdgeButton.
  - Bulb: power switch, brightness, temperature, colour, scenes, remove (confirmed).
  - Brightness / temperature: M3 Stepper with a level arc, crown/bezel control with
    haptic ticks; brightness down to 0 turns the light off.
- **Tile** (ProtoLayout Material 3): `[−] [power] [+]` and Warm / Cool.
- **Complication**: shows on/off and brightness; tap to toggle.

## Battery

- Wear OS normally routes traffic through the phone's Bluetooth proxy, which never
  reaches the LAN. The app asks for the Wi-Fi network explicitly (no internet
  capability required) and binds its sockets to it.
- Wi-Fi is held only while the app is on screen, and for ~5 s after a tile or
  complication tap.
- The complication never polls (`UPDATE_PERIOD_SECONDS=0`); the tile only refreshes
  when its cached state is older than a minute. Last known state is persisted.

## Build & install

Requirements: Android Studio / JDK 17+, Android SDK 36, a Wear OS 3+ watch
(minSdk 30) on the same Wi-Fi as the bulbs.

```bash
./gradlew :wear:assembleRelease
adb install -r wear/build/outputs/apk/release/wear-release.apk
```

The release build is signed with the local debug key for sideloading; use your own
signing config before distributing.

## Project layout

```
wear/src/main/java/com/wizwear/control/
  net/           WizClient (UDP protocol, discovery), WifiNetworkProvider
  data/          WizRepository, BulbStore (persistence), Presets (scenes, colours)
  ui/            Compose screens, Stepper adjust screen, M3 theme + palette
  tile/          ProtoLayout Material 3 tile
  complication/  Complication data source + tap-to-toggle receiver
```

## Not affiliated

WiZ is a trademark of Signify. This is an independent project that uses the bulbs'
local protocol.
