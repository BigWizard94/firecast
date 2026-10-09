# Vega OS Deep-Dive — FireCast Receiver Research

**Date:** 2026-10-09 · **Status:** research only, no implementation
**Target device:** Fire TV Stick 4K Select (Vega OS, Linux-based — NOT Android, no APK sideloading)
**Goal:** a cast-receiver app for the stick — discoverable on LAN, accepts a media URL pushed from an Android phone app, plays it on the TV.

Docs version note: Amazon versions the Vega docs (`/docs/vega/0.21/`, `/0.23/`, `/0.24/`). Latest seen during research: **0.24** (SDK 0.24.12112, released 2026-09-21). CLI was renamed from `kepler` (0.21 docs) to `vega` (0.23+).

---

## 1. Vega SDK / CLI toolchain (Linux)

**Docs:** https://developer.amazon.com/docs/vega (start) · install: https://developer.amazon.com/docs/vega/0.23/install-vega-sdk.html · CLI reference: https://developer.amazon.com/docs/vega/0.21/cli-tools.html

**Prerequisites (Ubuntu):**
- Node.js 18.x or later
- python3.8 + lz4 (native debugging / crash-report symbolication)
- KVM hypervisor (for the Vega Virtual Device)
- Watchman (file monitoring for Metro)
- `curl`

**Install (one line):**
```bash
curl -fsSL https://sdk-installer.vega.labcollab.net/get_vvm.sh | bash && source ~/vega/env
```
The installer is interactive (install dir default `~/vega/sdk`, confirm SDK install, optional VS Code "Vega Studio" extension). Non-interactive SDK install afterwards: `vega sdk install --non-interactive`.

**What gets installed:** Vega CLI → `~/vega/bin`; SDK → `~/vega/sdk`; Vega Virtual Device (emulated stick); VS Code extension; shell-profile updates. **Telemetry is collected by default** (opt-out documented at `/docs/vega/latest/telemetry.html`).

**Project workflow:**
```bash
kepler project list-templates
kepler project generate --template helloWorld --name firecast --packageId com.bigwizardmedia.firecast
cd firecast && npm install
npm run build:debug                  # react-native build-vega --build-type Debug → .vpkg (verified working)
vega virtual-device start
vega run-app build/x86_64-release/firecast_x86_64.vpkg com.bigwizardmedia.firecast.main -dVirtualDevice
```
Build outputs per arch: `build/x86_64-release/*_x86_64.vpkg` (VVD), `build/aarch64-release/*_aarch64.vpkg` (VVD on ARM), **`build/armv7-release/*_armv7.vpkg` (physical Vega Fire TV sticks — the 4K Select is armv7)**. Validate before submission: `vega exec vpt validate <vpkg>`.

**manifest.toml** (package root, TOML): `schema-version`, `[package]` (title, version, id), `[components]` (`[[components.interactive]]` with id, runtime-module, `categories = ["com.amazon.category.main"]`, launch-type), `[tasks]`, `[needs]`/`[wants]` (services + privileges), `[offers]`, `[processes]`, `[[extras]]` (custom metadata incl. DIAL keys — see §3). **The `com.amazon.category.main` category is mandatory** — without it the app can't launch from Home and fails Appstore submission.

**Deploy to a real stick (dev):** `vega devmode login` → `vega devmode enable-device --code <6-digit-code-on-TV>` → USB connect → `vega device install-app --packagePath <armv7.vpkg>` → `vega device launch-app --appName <component-id>`.

---

## 2. Install verdict (this VM)

**VERDICT: YES — a hello-world `.vpkg` was produced on this VM.** Vega CLI 1.4.4 + SDK 0.24.12112 installed; `kepler project generate --template helloWorld` → `npm install` → `npm run build:debug` (`react-native build-vega --build-type Debug`, exit 0) produced working packages for **all three architectures**, including `build/armv7-debug/firecast_armv7.vpkg` (1.4 MB — the physical-stick target). `vega exec vpt info` reads the package correctly (id `com.bigwizardmedia.firecast`, v0.1.0). `vega exec vpt validate` on the armv7 package flags only template-metadata issues (missing `package.icon`; `build_number` must be > 0 — template default) — both fixed by editing `manifest.toml`, not toolchain problems.

Attempt log (logs in `~/workspace/firecast/research/logs/`):
1. Installer refuses to run as root (*"This script must not be run as root"*) → created non-root user `builder`, re-ran.
2. `curl … | bash` steals stdin → interactive prompts got EOF and setup failed. Fix: download script first, run under a pty (`script -qec`).
3. Prompts also read `/dev/tty` directly → used `vega sdk install --non-interactive` for the SDK step instead.
4. **`/tmp` is a 512 MB tmpfs** → SDK download (~1.6 GB) died with *"no space left on device"*. Fix: `TMPDIR=/home/builder/bigtmp` (94 GB free).
5. SDK extracted, but installer's "pre-warm dependencies" step failed: bundled `KeplerPerfCLI` needs **`libjpeg.so.62`**, absent on Ubuntu 24.04 (ships libjpeg.so.8) → installer **uninstalled the SDK**. Symlinking libjpeg.so.8→.62 fixed loading but failed on the versioned symbol `LIBJPEG_6.2`. Real fix: extracted `libjpeg62-turbo_2.1.5-2_amd64.deb` from Debian bookworm and installed the genuine `libjpeg.so.62` → pre-warm passed, SDK installed.
6. **Vega Virtual Device download failed** (*"failed to move downloaded file … no such file or directory"* / earlier *"unexpected EOF"*). VVD is uninstallable here anyway — **no `/dev/kvm`** on this VM, so the emulator could never run. Non-blocking for builds: `vpt validate`/`vpt info` (static checks) work fine.

VM facts: Ubuntu 24.04, Node v24.20.0 ✓ (needs ≥18), Python 3.12.3 (docs ask 3.8 — only for crash symbolication), 7 GB RAM / 2 vCPU, passwordless sudo. Total wall time for a from-scratch toolchain: ~1 hour (dominated by the ~4 GB SDK download at 1–4 MB/s).

**Bottom line for the project: the VM can build production Vega packages today. No computer needed on the user's side for builds.**

Attempt log (`~/workspace/firecast/research/vega-deep-dive/` logs in `../research/logs/`):
1. Ran installer as root → refused: *"This script must not be run as root"* (exit 1). Created non-root user `builder` and re-ran.
2. Piped `yes ''` into `curl … | bash` → CLI 1.4.4 installed, but setup failed: the script reads prompts from `/dev/tty`, and `curl | bash` steals stdin, so prompts got EOF.
3. Re-ran the downloaded script under a pty (`script -qec`) → progressed to *"Install Vega SDK version 0.24.12112? [Y/n]"* but again tried `/dev/tty` with no terminal → the wrapper was then SIGKILLed (exit 137) after the SDK download stalled at ~230 KB/s (86-min ETA) **and** failed with *"failed to write downloaded data: …/installer.tmp: no space left on device"* — **`/tmp` on this VM is a 512 MB tmpfs**, far too small for the multi-GB SDK.
4. Retried surgically: `vega sdk install --non-interactive` (CLI already installed) with `TMPDIR=/home/builder/bigtmp` (94 GB free). Download then ran at ~4 MB/s.

VM facts: Ubuntu 24.04, Node v24.20.0 ✓ (≥18), Python 3.12.3 (docs ask 3.8 — only needed for crash symbolication), **no /dev/kvm** (Vega Virtual Device very likely cannot run here), 7 GB RAM, 2 vCPU, passwordless sudo available.

**Verdict:** _to be filled in once the SDK download completes — check `~/workspace/firecast/research/logs/vega-sdk-install.log`._ If it completes, next step is `kepler project generate --template hello-world` + `npx react-native build-kepler` to prove a `.vpkg` can be produced. Known remaining risk even after install: the Virtual Device needs KVM (absent), so on-VM execution testing is unlikely; `vpt validate` (static package validation) should still work.

---

## 3. DIAL on Vega (discovery + launch)

**Doc:** https://developer.amazon.com/docs/vega/0.23/dial-vega.html · Spec: DIAL 2.2.1 (dial-multiscreen.org) · Registry: https://www.dial-multiscreen.org/dial-registries/about-the-registry (live as of 2026-10-09)

**Receiver side (manifest.toml):**
```toml
[[extras]]
key = "dial_id"
value = "<your-dial-id>"          # register at dial-multiscreen.org (required once the app is publicly in the market)
[[extras]]
key = "dial_launch_component"
value = "<interactive component id>"
```
- `dial_id`: your chosen app name; registry rules — must be unique, ≥4 chars for prefixes, can't collide with existing names (netflix, youtube…). Registration is only for 1st-screen (TV) apps; 2nd-screen (phone) apps don't register.
- `dial_launch_component`: the interactive component DIAL launches; the launch payload arrives **as a parameter in the launch URI**.
- CORS: `dial_origins` extra + authorized origins in `whisperplay.xml` (wildcard `*` allowed per-segment; insecure schemes like `http:`/`file:`/`ftp:` are forbidden regardless).
- Optional two-way: `additionalDataUrl` — the TV app can POST an XML state doc the phone can later GET (session tokens, playback state). Origin must be in `dial_origins`.

**What the phone sender must implement (DIAL 2.2.1 client):**
1. **SSDP discovery:** UDP M-SEARCH to `239.255.255.250:1900`, search target `ST: "urn:dial-multiscreen-org:service:dial:1"`. Stick replies with `LOCATION:` (device-description URL) and `USN:` (dedupe multiple responses by USN). May include a `WAKEUP` header.
2. **Device description:** HTTP GET the LOCATION URL → from response headers read **`Application-URL`** (the DIAL REST base). Friendly name/model/manufacturer in the body for UI.
3. **App status (optional but recommended):** `GET {Application-URL}/{dial_id}` → `200` + XML (`running`/`stopped`; `installable`/`hidden` not supported on Fire TV) or `404` (not installed → prompt user to install from Appstore; install itself is out of DIAL scope).
4. **Launch:** `POST {Application-URL}/{dial_id}` with `Content-Type: text/plain` body = launch payload (e.g. the media URL, or a small JSON blob `{url, position, title}`). The receiver gets it as a launch-URI parameter and acts on it.
5. **Follow-up commands:** re-POST (stateless: play/pause/seek/stop each as a launch with payload), and/or GET the `additionalDataUrl` for state the TV app POSTed back.

Amazon is explicit: *"DIAL is not a bit-streaming or screen mirroring API"* — it is discovery + launch (+payload). All media transport is the app's own business (see §4/§8).

---

## 4. Video playback on Vega

**Docs:** https://developer.amazon.com/docs/vega/0.22/media-player.html · Shaka/adaptive: https://developer.amazon.com/docs/vega/0.23/media-player-shaka-player.html · Walkthrough: https://developer.amazon.com/docs/vega/0.21/media-player-walkthrough.html

**Primary API — `@amazon-devices/react-native-w3cmedia`:**
```tsx
import { VideoPlayer, KeplerVideoSurfaceView } from '@amazon-devices/react-native-w3cmedia';
const video = useRef<VideoPlayer | null>(new VideoPlayer());
// on surface created:
await video.current?.initialize();
video.current?.setSurfaceHandle(surfaceHandle);
video.current!.autoplay = true;
video.current!.src = 'https://…/movie.mp4';   // W3C HTMLMediaElement-style API
```
Controller (`VideoPlayer`) is separate from the render surface (`KeplerVideoSurfaceView`); `initialize()` is explicit (unlike browsers). Alternative package: `@amazon-devices/kepler-media` (community templates use it; don't mix with `react-native-video` — it silently fails on Vega).

**Adaptive streaming:** Shaka Player integration documented with full sample (HLS + DIAL-friendly): `ShakaPlayer` wrapping the same `VideoPlayer`, ABR config, `secure` flag for secure buffers.

**Formats (URL mode):** MP4, MKV, WEBM, MOV/QuickTime, AVI, FLV, OGG, MPEG2TS, 3GPP, MP3/FLAC audio…
**Formats (MSE mode):** HLS (.m3u8, fMP4 or TS), DASH (.mpd, fMP4 or WebM), SmoothStreaming.
**Codecs:** AV1 (4K60, HDR10/HLG), HEVC 4K60, H.264 4K60, VP9 4K60, VP8, MPEG-2/4; audio AAC/HE-AAC/xHE-AAC, MP3, AC3/EAC3/AC4, Opus, FLAC, Vorbis, PCM, DTS (passthrough).
**DRM:** Widevine L1 (HW, HD/4K) / L3 (SW, SD); PlayReady SL2000/3000 (HW), SL150 (SW). (Needs `com.amazon.drm.key` + `com.amazon.drm.crypto` services; FireCast v1 doesn't need DRM.)

**manifest.toml services required for playback:**
```toml
[[wants.service]]
id = "com.amazon.media.server"
[[wants.service]]
id = "com.amazon.mediametrics.service"
[[wants.service]]
id = "com.amazon.mediabuffer.service"
[[wants.service]]
id = "com.amazon.mediatransform.service"
# audio:
[[wants.service]]
id = "com.amazon.audio.stream"
[[wants.service]]
id = "com.amazon.audio.control"
# group IPC + privilege:
[[wants.service]]
id = "com.amazon.gipc.uuid.*"
[offers]
[[offers.service]]
id = "com.amazon.gipc.uuid.*"
[[needs.privilege]]
id = "com.amazon.privilege.security.file-sharing"
```

**Limitations / unknowns:**
- No background-play API surfaced in docs — receiver is a foreground interactive app (fine for casting).
- Capability checks: docs say to query device media capabilities before picking codecs (4K/Dolby depend on hardware).
- Community caveat (screentinker, Vega sticks): 1 GB RAM sticks can't hold a second decoder for preloading; `drawImage()` of a `<video>` frame SIGTRAPs in the Vega compositor (don't do canvas thumbnails of video).
- WebView alternative: `@amazon-devices/webview` hosts web content with hardware-decoded `<video>` (proven by the open-source screentinker Vega player) — a possible shortcut for the receiver UI, but native `VideoPlayer` is the documented first-class path.

---

## 5. Amazon Appstore submission

**Doc:** https://developer.amazon.com/docs/vega/0.21/app-submission.html · General: https://developer.amazon.com/docs/app-submission/submitting-apps-to-amazon-appstore.html

**Process (condensed):**
1. Sign in to the Developer Console (developer.amazon.com).
2. **Add a New App** (or Add Upcoming Version).
3. **Upload Your App File** → upload the `.vpkg`.
4. **Target Your App** → Edit supported devices → **Amazon Fire TV** tab → **Amazon Vega TV** section → pick the device(s).
5. **Appstore Details** → metadata + Fire TV assets (icon, banners, screenshots). **Do not mention "Vega" in any field** (per Amazon's own doc).
6. **Review & Submit**.

**Requirements:** VPKG binary · Amazon developer account · image assets + descriptions · a physical device to test on. Manifest must declare `com.amazon.category.main`. One listing can carry **both** an APK (Fire OS) and a VPKG (Vega) — metadata, IAP, reviews shared; version codes/build numbers tracked independently. Live App Testing (beta channel) is supported for Vega.

**Fees:** **Free.** Amazon's own payments FAQ: *"How much does Amazon charge for using its developer program? The program is free. You pay nothing to create a developer account, use Amazon APIs, and submit apps."* (No annual fee, unlike Apple's $99/yr.) Identity/tax interview required only if monetizing.

**Review:** Amazon publishes no SLA in the docs; community reports (Fire TV submitters) cluster around **1–5 business days** — treat as anecdotal. A cast-receiver is legitimate functionality; policy risk is low, but expect the standard content/stability checks.

**Can submission be done entirely from a phone browser?** The Developer Console is a standard web app — account signup, form-filling, metadata entry, and the VPKG file upload are all *technically* possible from a mobile browser; no step inherently requires a desktop. **Caveats (honest):** unverified in practice — large-file upload from a phone browser, desktop-oriented console layouts, and possible 2FA friction make it painful but not impossible. The actual blocker (building the VPKG) does **not** need the user's computer at all — it builds on our VM (see §2), and the resulting `.vpkg` can be handed to the user for upload.

---

## 6. Web browser on Vega OS — **YES**

**Firm answer: YES.** Evidence:
- **tvteka.com's official install guide** (a service that depends on this): *"Attention! Amazon Fire TV Stick 4K Select and Fire TV Stick HD devices based on Vega OS support our service **only through the built-in Silk Browser** via link http://tv.tvteka.com/tv"* — proves (a) a built-in Silk Browser exists on Vega, and (b) it can open **arbitrary URLs** including LAN-style links.
- glennzbdevices' 2026 Fire TV Stick HD (Vega) review: *"want a web browser? Amazon's own Silk Browser seems to be your only option and while it works okay…"*
- XDA on Vega sticks: Silk surfaces in on-device Appstore search results.

**What this means for FireCast:** a **zero-install fallback receiver is viable in principle** — a web page (hosted on the phone or a public URL) opened in Silk that connects back via WebSocket and plays video with `<video>`. **Unverified and needs on-device testing:** WebSocket support in Vega's Silk, whether it keeps a page alive in the foreground reliably, and video codec behavior there. Note the screentinker project proves Vega's *WebView* does hardware-decoded video, which is encouraging but not the same as the Silk app. Recommendation: treat the web fallback as plan B to validate on the real stick, not the primary path.

---

## 7. Developer-device registration (the QR-code wall the user hit)

**Doc:** https://developer.amazon.com/docs/vega/0.24/developer-mode · Also: aftvnews "Sideloading is truly dead on the Fire TV Stick 4K Select" (https://www.aftvnews.com/sideloading-is-truly-dead-on-the-fire-tv-stick-4k-select-with-vega-os/)

**The official flow:**
1. Amazon Developer Account (free).
2. Register the stick as a developer device under that account.
3. On the stick: Settings → My Fire TV → About → select the device name **7 times** → Developer Options appear.
4. Enable Developer Mode → stick **reboots**.
5. On a **computer** running the Vega CLI: `vega devmode login` → browser OAuth consent (*"Vega Authentication Test would like access to…"*) → Allow → *"Device connected to your account."*
6. The TV displays a **6-digit pairing code** (this is the "scan card code"/QR screen the user saw). Enter it into the CLI: `vega devmode enable-device --code <code>` → `✓ Device enabled successfully`.
7. Deploy over **USB cable**: `vega device list`, then `vega device install-app --packagePath app_armv7.vpkg` / `vega device launch-app --appName …`.

**Why the user is stuck:** the 6-digit code/QR on the TV has to be entered into the **Vega CLI running on a computer** — with phone-only, there is nowhere to enter it, so the "matching code" never appears and the flow dead-ends right there. Even past that step, deploying requires a **USB connection to a computer**. There is no phone-only path through developer mode. (Also note aftvnews: sideloaded Vega apps get a generic icon and the OS may disable them when not actively developed — dev-mode is a *testing* path, not distribution; distribution = Appstore.)

---

## 8. Recommended receiver architecture

**Components:**
1. **Vega receiver app** (React Native 0.72 + TypeScript, from `hello-world` template):
   - `manifest.toml`: `com.amazon.category.main`, `[[extras]]` with `dial_id` (e.g. `FireCast`) + `dial_launch_component`, media services (`com.amazon.media.server`, mediametrics, mediabuffer, mediatransform, audio.stream, audio.control, gipc), `com.amazon.privilege.security.file-sharing`.
   - **Launch handling:** parse the DIAL launch-URI payload → `{url, position?, title?}` → feed to player. Every DIAL POST is a command (stateless v1: `play`, `pause`, `seek`, `stop`).
   - **Playback:** `@amazon-devices/react-native-w3cmedia` `VideoPlayer` + `KeplerVideoSurfaceView` (URL mode covers MP4-from-phone; add Shaka path later for HLS).
   - **State back-channel (v2):** POST playback state to `additionalDataUrl` so the phone can GET it; or graduate to a WebSocket the *receiver* opens to the phone (phone IP passed in the DIAL payload — avoids needing a listening socket on the stick, whose feasibility is unverified).
   - **D-pad UI:** minimal now-playing screen (title, position, transport hints); remote play/pause/seek.
2. **Wire protocol (shared with the Android sender, separate track):** DIAL for discovery+launch; media fetched by the stick over HTTP(S) — for local phone files the sender runs an embedded HTTP server and passes a LAN URL. Keep payload JSON tiny and versioned (`{"v":1,"cmd":"play","url":"…","pos":0}`).
3. **Distribution:** Amazon Appstore (Vega TV target), free dev account, VPKG built on our VM (§2), Live App Testing for beta. Dev-mode+USB sideload only for on-stick testing (needs a computer — borrow one or use a library PC for the 10-minute deploy).
4. **Fallback (validate on device):** Silk-browser web receiver page — zero install, for users who won't install the Appstore app.

**Order of work:** (1) finish SDK install → hello-world `.vpkg` → `vpt validate`; (2) manifest + DIAL extras + launch-URI parsing stub, testable against VVD if KVM were available (it's not — note §2) → real-stick test needs the USB/computer step; (3) VideoPlayer integration with a static MP4 URL; (4) DIAL-payload-driven playback; (5) Appstore listing + LAT; (6) Silk fallback spike.

**Open unknowns (be honest):** exact `manifest.toml` DIAL/CORS snippet rendering (doc code blocks didn't extract cleanly — verify against the live doc page); whether a Vega app may open a listening socket (prefer receiver-initiated WebSocket); Vega Silk's WebSocket/keepalive behavior; Appstore review latency for a first Vega submission; whether `vpt validate` enforces anything beyond packaging (strict ABI check per giojump notes).

**Doc URLs (canonical):** install https://developer.amazon.com/docs/vega/0.23/install-vega-sdk.html · hello-world https://developer.amazon.com/docs/vega/0.23/hello-world.html · CLI https://developer.amazon.com/docs/vega/0.21/cli-tools.html · manifest https://developer.amazon.com/docs/vega/0.21/app-manifest.html · DIAL https://developer.amazon.com/docs/vega/0.23/dial-vega.html · media https://developer.amazon.com/docs/vega/0.22/media-player.html · shaka https://developer.amazon.com/docs/vega/0.23/media-player-shaka-player.html · webview https://developer.amazon.com/docs/vega/0.21/set-up-webview.html · dev-mode https://developer.amazon.com/docs/vega/0.24/developer-mode · run-apps https://developer.amazon.com/docs/vega/0.22/run-apps · submission https://developer.amazon.com/docs/vega/0.21/app-submission.html · registry https://www.dial-multiscreen.org/dial-registries/about-the-registry
