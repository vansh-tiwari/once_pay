# UltraWideCam (OncePay) 📸⚡

> **Bypassing Physical Hardware Failures with Android Camera2, Edge ML & Direct UPI Routing**  
> *Resurrecting a broken smartphone camera by directly commandeering the functional 0.6x Ultra-Wide optical sensor.*

---

## 📌 The Problem Statement

Modern flagship smartphones (such as the Samsung Galaxy S23 Ultra) feature multi-camera arrays, where each lens is a distinct physical sensor. When the **primary 1.0x camera sensor physically breaks** (shattered optics, mechanical OIS/autofocus actuator failure, or sensor sensor-burn), the device is rendered functionally crippled:

1. **Hostage to the Broken Sensor**: Standard camera apps (Google Camera, Samsung Camera) and third-party apps (WhatsApp, Instagram) automatically default to `cameraId 0` (the broken 1.0x wide lens), presenting a black screen, crash loop, or distorted feed.
2. **Broken Daily Transactions**: In India and UPI-enabled regions, daily commerce depends on scanning QR codes. Banking and payment apps (Google Pay, PhonePe, Paytm) do not provide multi-lens toggles on their scan screens. When the primary sensor fails, **you cannot scan QR codes to pay for groceries, transit, or meals**.
3. **Prohibitive Repair Costs**: Replacing an entire multi-camera module is expensive, delayed, or impractical.

**The Crucial Insight**: While the primary 1.0x sensor was completely destroyed, the secondary **0.6x Ultra-Wide optical sensor was 100% intact, healthy, and operational**. Yet no existing application on the Play Store allows users to lock the entire phone's camera and payment workflow exclusively to the physical 0.6x hardware lens.

---

## 💡 The Solution: UltraWideCam

**UltraWideCam** is a native, hardware-aware Android camera and payment engine built from the ground up to solve this exact hardware dilemma:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PHYSICAL SMARTPHONE HARDWARE                    │
│   [ Broken 1.0x Wide Sensor ❌ ]     [ Intact 0.6x Ultra-Wide Sensor ✅ ] │
└───────────────────────┬──────────────────────────────────┬─────────────┘
                        │ (Bypassed)                       │ (Direct Pinning)
                        ▼                                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Camera2 Interop + CameraX                        │
│         Queries SENSOR_INFO_PHYSICAL_SIZE & focal lengths              │
│               Directly binds 0.6x Hardware Camera ID                   │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   60 FPS VIEW FINDER & FRAMING ENGINE                  │
│       • 0.6x Native Ultra-Wide (Full Field of View)                    │
│       • 1.0x Standard Crop (Software digital framing)                  │
│       • 2.0x Telephoto Crop (In-sensor digital zoom)                   │
│       • Manual Exposure Drag (+/- EV), Tap-to-Focus Metering, Torch    │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│             ON-DEVICE MACHINE VISION & INTENT ROUTER                   │
│       • Google ML Kit Barcode Analyzer (<15ms Zero-Copy)               │
│       • Strict UPI URI & VPA Regex Validation Gate                     │
│       • System Clipboard Auto-Priming                                  │
│       • Direct Google Pay / System Intent Launch                       │
└────────────────────────────────────────────────────────────────────────┘
```

1. **Hardware-Level Sensor Pinning (`CameraHelper.kt`)**:  
   Queries `CameraCharacteristics` via Android Camera2 Interop, calculates true 35mm equivalent focal lengths from physical sensor dimensions, identifies the physical 0.6x sensor ID, and forces Jetpack CameraX to bind directly to it.
2. **Everyday Camera Replacement (`CameraActivity.kt`)**:  
   Restores full normal photography and videography. Implements digital framing presets (`.6x` native wide, `1x` standard crop, `2x` telephoto crop) directly on the high-resolution 0.6x sensor without ever attempting to activate the broken physical hardware sensors.
3. **Instant UPI & Google Pay Resolution**:  
   Runs real-time on-device barcode scanning. When a merchant QR code is framed:
   * The app extracts and sanitizes the UPI ID (VPA).
   * Copies the VPA to the system clipboard.
   * Directly launches **Google Pay** (`com.google.android.apps.nbu.paisa.user`), bypassing third-party intent payment blocks imposed by banks. The user simply taps "Pay UPI ID" and pastes the clipboard payload.
4. **Payment Hub & Offline History Ledger (`PaymentHubActivity.kt`)**:  
   Includes a Quick Settings pull-down status tile (`OncePayTileService`), gallery QR picker, direct VPA transfer input, and a local encrypted transaction history ledger.

---

## ⚡ Technical Highlights & Architecture

* **Zero-Allocation Perception**: Uses an asynchronous `ImageAnalysis` analyzer with zero-copy `ImageProxy` lifecycles to prevent garbage collection pauses and frame drops on the 60 FPS viewfinder.
* **Defensive Intent Sandboxing**: Strict RFC 3986 and VPA regex validation (`^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z]{2,64}$`) prevents URI parameter injection.
* **Zero Network Surface Area**: UltraWideCam does **not** request `android.permission.INTERNET`. All optical recognition runs 100% on-device via Google ML Kit. Zero telemetry, zero cloud dependencies, zero data exfiltration risk.
* **Modern Android Stack**: Built with Kotlin, Jetpack CameraX 1.4, ViewBinding, Material Design 3 (Nordic Obsidian & Mineral Mint palette), and Android SDK 35 compatibility.

---

## 🤖 AI-Era Engineering & Agent Directives

This project adheres to cutting-edge AI-assisted software engineering practices:

* **[`AGENT.md`](AGENT.md)** — Comprehensive autonomous agent protocol, cognitive hierarchies, self-healing compilation loop, and defensive coding directives.
* **[`GEMINI.md`](GEMINI.md)** — Multimodal Edge & Vision AI specification covering token economics, dynamic ROI cropping, and prompt-injection neutralization for real-world optical inputs.
* **[`docs/AI_ARCHITECTURE.md`](docs/AI_ARCHITECTURE.md)** — Systems architecture whitepaper featuring topological diagrams, frame memory lifecycles, and multi-agent development workflows.

---

## 📂 Repository Structure

```
UltraWideCam/
├── AGENT.md                         # Autonomous Agent Protocol & Engineering Directives
├── GEMINI.md                        # Multimodal Vision AI & Token Economics Spec
├── docs/
│   └── AI_ARCHITECTURE.md           # Systems Architecture Whitepaper & Topology
├── app/src/main/
│   ├── java/com/ultrawidecam/
│   │   ├── MainActivity.kt          # Launcher router
│   │   ├── CameraActivity.kt        # Primary 0.6x camera, viewfinder, controls, ML Kit QR
│   │   ├── CameraHelper.kt          # Physical sensor focal length calculation & 0.6x pin
│   │   ├── PaymentHubActivity.kt    # Bento-style payment tools & offline scan ledger
│   │   ├── PaymentHistoryHelper.kt  # Sandboxed SharedPreferences scan history vault
│   │   └── OncePayTileService.kt    # Android Quick Settings pulldown tile service
│   ├── res/                         # Liquid glass UI layouts, animations, and drawables
│   └── AndroidManifest.xml          # Hardware camera features, permissions & intent queries
├── build.gradle                     # Top-level Gradle configuration
└── settings.gradle
```

---

## 🛠️ Building & Running

### Requirements
* **Android Studio**: Koala / Ladybug or newer
* **JDK**: Version 17 or 21
* **Android Device / Emulator**: Running Android 8.0 (API 26) through Android 15 (API 35)

### Build Commands
```bash
# Verify Kotlin compilation
./gradlew compileDebugKotlin

# Run static analysis and linting (0 errors policy)
./gradlew lintDebug

# Assemble debug APK
./gradlew assembleDebug
```

---

## 👤 Author & Maintainer

**Vansh Tiwari**  
* GitHub: [vansh](https://github.com/vansh)  
* Email: [tiwari1998@hotmail.com](mailto:tiwari1998@hotmail.com)
