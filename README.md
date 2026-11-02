# Ultima Control

An unofficial Android companion app for the **Ultima Prime Aura** earbuds
(classic Bluetooth SPP, Bluetrum "ABMate" protocol).

The official *Ultima Link* app (`com.yscoco.kanha`) is a white-label YSCOCO
build. This app talks to the earbuds directly, using the same protocol the
vendor SDK does, but with a Material 3 UI and a few controls the official app
does not expose.

> Not affiliated with Ultima, YSCOCO or Bluetrum. Reverse-engineered from the
> vendor APK for personal use. Use at your own risk.

---

## Requirements

| | |
|---|---|
| Android | 7.0+ (minSdk 24, targetSdk 34) |
| Bluetooth | Classic BR/EDR + SPP (the earbuds must be **paired** in system Bluetooth first) |
| Build | JDK 17+ (JDK 21 used), Gradle 8.9, Android Gradle Plugin 8.7.3, Android SDK 34 |

Dependencies: `com.google.android.material:material:1.12.0` (AndroidX enabled).
There is no Gradle wrapper in the repo — use your own Gradle 8.9, or generate
one with `gradle wrapper`.

## Build

```bash
# local.properties must point at your SDK
echo "sdk.dir=$HOME/android-sdk" > local.properties

JAVA_HOME=/path/to/jdk21 ANDROID_HOME=$HOME/android-sdk \
  gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

- `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (Android 12+)
- `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (Android 11 and below)

## How it works

The control link is **classic Bluetooth SPP (RFCOMM)**, not BLE. The app tries
the standard SPP UUID first, then the vendor's custom one.

```
UUID (default) 00001101-0000-1000-8000-00805F9B34FB
UUID (custom)  B6632277-0642-458B-A7A0-23FB1DC92C93
```

Frame format (5-byte header, no magic, no CRC):

```
[seq:4bits][cmd][cmdType][frameInfo][len][payload ...]
  seq        0-15, increments per frame
  cmdType    1 = request, 2 = response, 3 = notification
  frameInfo  ((fragmentCount - 1) << 4) | fragmentIndex
```

Payloads are split into **15-byte fragments** (`RequestSplitter(15)` in the
vendor SDK); a single oversized frame is silently ignored by the buds.

### Commands used

| ID | Command | Payload |
|---|---|---|
| `0x20` | EQ | `[10, mode, 10 x gain]` |
| `0x21` | Music control | `[2]` play, `[3]` pause, `[4]` prev, `[5]` next, `[1, volume]` |
| `0x22` | Key remap | `[keyType, 1, function]` (`5` = vol up, `6` = vol down) |
| `0x23` | Auto shutdown | `[0]` now, `[0xFF]` cancel |
| `0x24` | Factory reset | – |
| `0x25` | Work mode | `[0]` normal, `[1]` gaming |
| `0x26` | In-ear detection | `[0/1]` |
| `0x27` | Device info | `[infoType, 0]` |
| `0x29` | Language | `[0]` EN, `[1]` CN |
| `0x2A` | Find device | `[1]` ring, `[0]` stop |
| `0x2B` | Auto answer | `[0/1]` |
| `0x2C` | ANC mode | `[0]` off, `[1]` ANC, `[2]` transparency |
| `0x2E` | LED | `[0/1]` |
| `0x2F` | Clear pairings | – |
| `0x30` | ANC gain | `[0-10]` |
| `0x31` | Transparency gain | `[0-10]` |
| `0x32` | 3D audio | `[0/1]` |
| `0x33` | Multipoint | `[1, 1, on]` |
| `0x41` | Spatial audio | `[0/1]` |
| `0x42` | LDAC | `[0/1]` |

## Screens

- **Connection** – SPP connect/disconnect, offline simulator.
- **Volume** – the phone's media stream (`AudioManager`), kept in sync with
  hardware keys via `android.media.VOLUME_CHANGED_ACTION`.
- **Earbud volume (hardware)** – experimental `0x21 01 <0-127>` control.
- **Noise control** – ANC off / on / transparency.
- **Sound** – gaming mode, LDAC, 3D, spatial audio, multipoint, in-ear,
  auto-answer, LED.
- **Playback**, **Find my earbuds**, **Equalizer** presets.
- **Advanced** – ANC/transparency gain, language, auto-off, clear pairings,
  factory reset, long-press gesture remap.
- **Debug** – raw hex frame sender and SPP log.

## Current limitations

These are properties of the earbuds' firmware, not bugs in the app UI.

1. **The link is send-only.** The earbuds never send a single byte back over
   SPP — no responses, no notifications. Device information (battery, firmware,
   ANC state) is therefore unavailable, and `Refresh all info` only fills the
   debug log. The vendor app works around this by reading battery from the
   **BLE advertisement beacon**, which this app does not implement.
2. **Switches show local state, not device state.** Because of (1), a toggle
   reflects what you last pressed, not what the earbuds are actually doing.
3. **The phone's media volume may not affect the buds' output.** The buds
   report `Absolute Volume not supported` over AVRCP, yet in practice the
   phone's stream volume does not change what they emit. The slider is accurate
   for Android's stream, but the buds may ignore it.
4. **The earbud hardware volume command is experimental.** `0x21 01 <0-127>` is
   accepted by the buds (it changes the value they report) but no audible
   change was observed on this firmware. It is a single byte; the 0-127 scale
   is inferred, not documented.
5. **The custom EQ preset sends placeholder gains.** `Custom` sends mode `0`
   with ten zeroed band gains; there is no per-band editor yet.
6. **Gesture remap overwrites the existing action.** The current mapping
   cannot be read back, so remapping long-press replaces whatever it did
   (often ANC or play/pause). A factory reset restores the defaults.
7. **No battery, no auto-connect, no persistence.** Nothing is stored between
   launches beyond Android's own Bluetooth pairing and volume state.
8. **Bluetooth must be toggled manually on some ROMs.** On the TECNO KG7n
   used for development, `svc bluetooth enable` is a no-op.
9. **Single-device assumption.** The app connects to one earbud set per
   session; multipoint pairing is configured on the buds, not here.
10. **Simulator is partial.** `Simulate without hardware` answers device-info
    requests and a subset of commands, enough to exercise the UI.

## Troubleshooting

- **"No paired devices"** – pair the earbuds in system Bluetooth settings first.
- **Connect succeeds but nothing works** – the SPP channel accepts writes even
  when the buds are not listening to a given command; check the debug log.
- **Buds silent after using the earbud-volume slider** – the value was sent too
  low. Raise it with the buds' own touch controls (or send a higher value).
- **Volume jumps on its own** – the earbuds send AVRCP volume notifications;
  Android may follow them.

## License

No license file yet — all rights reserved by the author until one is added.
