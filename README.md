<div align="center">

# 🚌 Now We Wait

**Scan-free, real-time Bizkaibus arrivals — straight from your GPS.**

*No QR codes. No paper timetables. Open the app, see your bus.*

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Backend](https://img.shields.io/badge/Backend-Hono%20%2B%20TypeScript-E36002?logo=hono&logoColor=white)](https://hono.dev)
[![Deployed on Railway](https://img.shields.io/badge/Deployed%20on-Railway-0B0D0E?logo=railway&logoColor=white)](https://railway.app)
[![CI](https://github.com/Zombiemunk/NowWeWait/actions/workflows/android-build.yml/badge.svg)](https://github.com/Zombiemunk/NowWeWait/actions)

</div>

---

## What is Now We Wait?

Bizkaibus stop signs have a small QR code that you're supposed to scan to see when your next bus arrives. The problem: you need perfect lighting, you have to fish your phone out, open the camera, hold it steady over the sign, *wait* for the redirect to load in your browser — and then the page barely fits your screen.

**Now We Wait** fixes this entirely. It uses your phone's GPS to automatically find the three closest bus stops around you and fetches their live arrival times in a single network request, all before you'd have finished scanning that QR code. Cold start to live arrivals in under 3 seconds.

It connects to the official **Bizkaibus SOAP Web Service** (`GetPasoParada`) in real time — the same data source the QR codes point to — so arrival times are always accurate to the second.

---

## Features

- 📍 **Automatic nearby stop detection** — Uses GPS to find your 3 closest Bizkaibus stops the moment you open the app
- ⚡ **Single round-trip loading** — Stop discovery + live arrival times fetched in one `/nearby` request, consistently under 3 seconds cold
- ⭐ **Favourites** — Pin stops you use regularly with drag-and-drop reordering; they auto-refresh every 20 seconds
- 🔍 **Stop search** — Fuzzy-search by stop name or 4-digit stop code (e.g. `0012`)
- 📵 **Offline mode** — Caches the last successful response in Room; shows an amber *"Offline · cached Xs ago"* badge when you lose signal
- 🏠 **Homescreen widget** — Glance-powered widget in three sizes (2×1, 4×1, 4×2) with a pinned stop or auto-nearest mode
- 🌍 **Trilingual** — English, Spanish, and Basque (Euskara) fully localised
- 🌑 **Dark mode** — Follows system theme with Material 3 dynamic colour

---

## Architecture

```
┌─────────────────────────────────┐        HTTPS / JSON
│  Android App (Kotlin + Compose) │ ──────────────────────────► ┌──────────────────────────┐
│                                 │                              │  Hono TypeScript Backend │
│  • FusedLocationProviderClient  │ ◄────────────────────────── │  (Railway — always on)   │
│  • Retrofit2 + OkHttp3          │    stop + arrivals payload   │                          │
│  • Room DB (offline cache)      │                              │  • SQLite  (1,968 stops) │
│  • Hilt DI                      │                              │  • SOAP XML parser       │
│  • Jetpack Glance widget        │                              │  • UTM → WGS84 converter │
│  • WorkManager (widget refresh) │                              └──────────┬───────────────┘
└─────────────────────────────────┘                                         │  SOAP XML
                                                                            ▼
                                                               Bizkaibus Web Service
                                                               (GetPasoParada — live)
```

### API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/nearby?lat=&lon=&n=3` | Returns the *n* closest stops with live arrivals attached |
| `GET` | `/stops?q=<text>` | Fuzzy-search stops by name or 4-digit code |
| `GET` | `/stops/:id` | Stop metadata (name, coordinates, municipality, lines) |
| `GET` | `/stops/:id/arrivals` | Live SOAP arrivals for a single stop |
| `GET` | `/alerts?stop=<id>` | Active service alerts for a stop or its lines |

---

## Tech Stack

### Android App
| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Dependency Injection | Hilt 2.55 |
| Networking | Retrofit 2.11 + OkHttp 4.12 + kotlinx.serialization |
| Local Database | Room 2.7 (KSP) |
| Preferences | DataStore |
| Widget | Jetpack Glance 1.1 |
| Background Tasks | WorkManager 2.9 |
| Location | FusedLocationProviderClient (Google Play Services) |
| Navigation | Jetpack Navigation3 |
| Min SDK | Android 8.0 (API 24) |

### Backend
| Layer | Technology |
|-------|-----------|
| Runtime | Node.js 20 |
| Framework | Hono 4 |
| Language | TypeScript 5.8 |
| Database | SQLite via better-sqlite3 |
| XML Parsing | fast-xml-parser 5 |
| Coordinate Conversion | Custom UTM Zone 30N → WGS84 math |
| Deployment | Railway (Docker, Debian-based) |

---

## Getting Started

### Prerequisites
- Android Studio Meerkat or newer (for the Android app)
- Node.js 20+ and npm (for the backend)
- JDK 17

### Run the backend locally

```bash
cd backend
npm install
npm run dev
# Server starts on http://localhost:3000
```

To re-ingest all stops from the Bizkaibus API (first time setup):

```bash
# From the backend directory
npx tsx src/ingestion.ts
```

This fetches all ~1,968 stops across 112+ municipalities in Bizkaia, converts their UTM Zone 30N coordinates to WGS84 lat/lng, and saves them to `stops.db`.

### Build the Android app

```bash
cd android
./gradlew assembleDebug
# APK output: android/app/build/outputs/apk/debug/app-debug.apk
```

By default the app connects to the **production Railway backend** at `https://nowwewait-production.up.railway.app/`. To point it at your local backend, update `BASE_URL` in [`NetworkModule.kt`](android/app/src/main/java/com/example/nowwewait/di/NetworkModule.kt).

### Install on your phone

Transfer `app-debug.apk` to your Android device, allow installation from unknown sources when prompted, and install. Grant location permission on first launch.

---

## Pre-built APKs

Ready-to-install debug APKs are committed to the [`/builds`](builds/) folder after every push to `main` via GitHub Actions CI.

👉 **[Download latest APK](builds/nowwewait-v1.0-debug.apk)**

> **Note:** Because this is a sideloaded debug build you'll need to enable *Install from unknown sources* in your Android settings.

---

## Project Structure

```
NowWeWait/
├── android/                    # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/java/
│       ├── data/               # Room entities, DAOs, Retrofit API interface
│       ├── di/                 # Hilt modules (Network, Database, Location)
│       ├── location/           # GPS location tracker
│       ├── theme/              # Material 3 colour scheme and typography
│       ├── ui/                 # Compose screens (Home, Favourites, Search, Detail)
│       └── widget/             # Jetpack Glance homescreen widget
├── backend/                    # Hono TypeScript API server
│   └── src/
│       ├── index.ts            # Route definitions and SOAP client
│       ├── ingestion.ts        # Stop data fetcher and SQLite populator
│       └── utils/              # UTM → WGS84 coordinate converter
├── builds/                     # Pre-compiled APK releases
└── .github/workflows/          # Android CI/CD (GitHub Actions)
```

---

## Privacy

Now We Wait does **not** collect, store, or transmit your personal data. GPS coordinates are sent only to the self-hosted backend solely to determine which bus stops are nearby. No analytics, no tracking, no accounts.

The `ACCESS_BACKGROUND_LOCATION` permission is declared for the optional homescreen widget's *Auto Nearest Stop* mode. It is never requested unless you explicitly configure the widget to use automatic location.

---

## Contributing

This is a personal project built for daily use in Bizkaia. Issues and pull requests are welcome — especially improvements to the stop ingestion logic, UI polish, or widget behaviour.

---

## License

MIT © 2026 — do whatever you like with it, just don't make me wait for the bus.
