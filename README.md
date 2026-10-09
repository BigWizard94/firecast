# FireCast (working title)

An Android sender app that casts phone media to a TV receiver over the local
network — and actually works, unlike the fake "cast to Fire Stick" apps on the
Play Store.

## Why it works

A stock Fire TV Stick listens for nothing except DIAL (app-launch only), so
sender-only "cast" apps are lying by architecture. FireCast pairs a phone
**sender** with a real **receiver** on the TV and speaks an open protocol
between them.

## Layout

- [`PROTOCOL.md`](PROTOCOL.md) — the complete wire protocol spec (v1, JSON over
  WebSocket, receiver-agnostic). **Read this first.** One sender drives two
  receivers: a Vega OS Appstore receiver (Fire TV Stick 4K Select / 3rd-gen 4K)
  and a Fire OS sideload-APK receiver (older sticks).
- [`sender/`](sender/) — the Android sender app (Kotlin, Material 3, minSdk 26).

## Sender highlights

- Discovery: SSDP M-SEARCH → DIAL device description → app state/launch,
  mDNS `_firecast._tcp` fallback, manual IP entry
- WebSocket control channel implementing PROTOCOL.md (OkHttp), with PIN
  pairing on first connect
- Embedded NanoHTTPD server (single ~100KB dep, zero transitive deps) serving
  local files to the LAN with HTTP Range support so the receiver can seek
- Share-sheet integration (shared videos / links), file picker, paste-URL field
- Device list + now-playing screens with transport controls

## Build

Local (needs JDK 17 + Android SDK, compileSdk 34):

```sh
./gradlew :sender:assembleDebug
# APK: sender/build/outputs/apk/debug/sender-debug.apk
```

Or push to GitHub — `.github/workflows/build.yml` builds the debug APK on
every push and uploads it as an artifact.

## License

TBD — MIT is the intended choice (matches the open-source-first goal).
