# 📡 DisasterMesh (OmniSight-XR)

> **Offline-first emergency mesh network for Android — no internet, no towers, no infrastructure.**

[![Build](https://github.com/tk24436/DisasterMesh/actions/workflows/android.yml/badge.svg)](https://github.com/tk24436/DisasterMesh/actions)
![Platform](https://img.shields.io/badge/Platform-Android-green)
![Language](https://img.shields.io/badge/Language-Kotlin-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## 🎯 The Problem

When floods, earthquakes, or industrial accidents strike, the first thing that fails is the communication infrastructure. Cell towers go down. Internet dies. Emergency responders become isolated. DisasterMesh was built to fix that.

## 💡 The Solution

Every Android phone running DisasterMesh automatically becomes a **node** in a self-healing, leaderless peer-to-peer mesh network — using only the Bluetooth and Wi-Fi radios already in every phone. **No special hardware. No SIM card. No internet.**

---

## ✨ Features

| Feature | Description |
|---|---|
| 📡 **Mesh Networking** | Multi-hop P2P mesh via Google Nearby Connections (BLE + WiFi-Direct) |
| 🆘 **SOS Broadcasts** | Priority-sorted emergency alerts relayed across the entire mesh (TTL-limited flood) |
| 💬 **Mesh Chat (DMs)** | Direct messages that route through multiple hops to reach the target node |
| 🤖 **Offline AI First Aid** | On-device first aid assistant — works with zero internet |
| 💓 **Heartbeat Protocol** | Self-healing peer discovery — detects online/offline status with 45s accuracy |
| 🔄 **Alert Sync** | New nodes joining the mesh are instantly synced with all existing SOS alerts |
| 🌑 **Dark Mode UI** | Clean, emergency-optimized dark interface |

---

## 🏗️ Architecture

```
MainActivity
├── MeshService (Foreground Android Service)
│   └── MeshManager.kt
│       ├── Google Nearby Connections API
│       ├── Heartbeat Protocol (peer discovery)
│       ├── SOS relay queue (priority-sorted)
│       └── DM routing (targeted mesh flood)
├── BroadcastFragment   → Send/receive SOS alerts
├── MessagesFragment    → Peer list + DM chat
├── AiHelperFragment    → Offline first aid AI
├── SettingsFragment    → Node identity + diagnostics
└── Room DB             → Persistent alerts + messages
```

### The Heartbeat Protocol

The core innovation. Every 10 seconds, each phone floods a unique heartbeat packet with its name and a UUID. All receiving nodes:
1. Check the UUID against seen IDs → **drop if duplicate** (loop prevention)
2. Update the sender's "last seen" timestamp
3. Relay to all other connected neighbors

If a device goes silent, it naturally decays to **Offline** after 45 seconds. No ghost devices. No stale identities.

---

## 🚀 Getting Started

### Prerequisites
- Android 8.0+ (API 26+)
- Bluetooth and Wi-Fi enabled
- Location permission granted (required by Android for Nearby API)

### Build from Source

```bash
git clone https://github.com/tk24436/DisasterMesh.git
cd DisasterMesh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First Launch
1. On first open, you'll be prompted to **set your Node Identity** (your name on the mesh).
2. Enable Bluetooth, Wi-Fi, and Location when prompted.
3. Tap **Join Mesh Network** — you're live!

---

## 🤖 On-Device AI (Snapdragon 8 Elite / OnePlus 15)

For devices running **Snapdragon 8 Elite (SM8850)**, DisasterMesh supports upgrading the first-aid AI from a knowledge base to **Gemma 4 2B running on the Hexagon V81 NPU** via Qualcomm AI Runtime (QAIRT 2.47.0).

### Setup
```bash
adb push gemma4_2b_SM8850.litertlm /sdcard/Android/data/com.example.disastermesh/files/
```

Add the required native libraries to `jniLibs/arm64-v8a/`:
- `libLiteRtDispatch_Qualcomm.so`
- `libQnnHtp.so`
- `libQnnHtpV81Skel.so`
- `libQnnHtpV81Stub.so`
- `libQnnSystem.so`
- `libGemmaModelConstraintProvider.so`

> On unsupported devices, the app automatically falls back to the offline keyword-based knowledge base.

---

## 📦 Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Mesh Radio | Google Nearby Connections API |
| Network Strategy | P2P_CLUSTER |
| Database | Room (SQLite) |
| AI (Standard) | Offline knowledge base (Kotlin) |
| AI (Snapdragon) | LiteRT-LM + QNN HTP / Hexagon V81 |
| Build | Gradle (AGP 8.x) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

---

## 🤝 Contributing

1. Fork the repo
2. Create your feature branch: `git checkout -b feat/my-feature`
3. Commit your changes: `git commit -m 'feat: add my feature'`
4. Push to the branch: `git push origin feat/my-feature`
5. Open a Pull Request

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgements

- [Google Nearby Connections](https://developers.google.com/nearby/connections/overview) for the mesh radio layer
- [QNN On-Device OnePlus15](https://github.com/carrycooldude/QNN-On-Device-OnePlus15) — reference implementation for Gemma 4 2B on SM8850
- [Google AI Edge / LiteRT-LM](https://github.com/google-ai-edge/litert-samples) — on-device LLM runtime
- Qualcomm QAIRT 2.47.0 SDK

---

*Built for the Snapdragon Multiverse Hackathon 🇮🇳*
