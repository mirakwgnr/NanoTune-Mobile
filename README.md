# NanoTune for Android

A fast, lightweight local music and synced lyrics player for **Android 8.0+**, built with **Kotlin**, **Jetpack Compose**, **Material 3**, and **AndroidX Media3**.

The official Android port of **[NanoTune (Desktop)](https://github.com/mirakwgnr/nanotune)**—bringing the same real-time audio effects, word-synced lyrics, and dynamic album-cover theming to your phone and smartwatch.

---

## Installation

1. Download **`NanoTune.apk`** from the [Releases](../../releases) page.
2. Open **`NanoTune.apk`** on your Android device (Android 8.0 or higher) and allow installation from unknown sources if prompted.
3. Grant audio/storage permission on first launch to scan your local music folders.

---

## Features

### Audio Playback & Library
- **Format Support**: Plays **FLAC** (16/24-bit), **WAV**, **MP3**, **M4A**, **OGG**, **OPUS**, and **AAC**.
- **Flexible Folder Scanning**: Scan all audio on your device or pick specific music folders, with local JSON and cover caching for instant startup.
- **Smart Album View**: Automatically groups tracks by album and generates single, split, or 2×2 collage covers when an album contains multiple artworks.
- **Smartwatch & Bluetooth Sync**: Exposes a browsable `MediaLibraryService` queue with rotary/bezel seek and optimized album artwork for Samsung Galaxy Watch, Wear OS, and Bluetooth head units.

### Real-Time Audio FX
Built-in PCM audio processor with live controls and presets:
- **Speed**: `0.60x` to `1.40x`
- **Pitch**: `±6.0` semitones (with optional speed-linked pitch for natural vinyl tone)
- **Bass Boost**: Warm `155 Hz` low-end boost
- **Hall Reverb**: Stereo room reverb
- **Lo-Fi Muffle**: `1400 Hz` low-pass filter
- **Presets**: *Normal*, *Slowed + Reverb*, *Warm Bass*, *Sped Up*, and *Lo-Fi Muffle*

### Synced Lyrics & Editor
- **Multiple Formats**: Supports word-by-word **`.ELRC`**, line-synced **`.LRC`**, plain text, and embedded **FLAC**, **MP3 (`USLT`)**, and **M4A (`©lyr`)** lyrics.
- **Smooth Scrolling**: High-frame-rate lyrics view with inertial touch fling, tap-to-seek lines, and word-level progress highlighting.
- **Built-In Editor**: Paste, edit, and timestamp lyrics directly inside the app while listening.

### Adaptive UI
- **Dynamic Album Colors**: Automatically extracts colors from the active album cover to theme the interface and star badge (with a silver-grey fallback for black-and-white covers).
- **Liquid Glass Mode**: Optional frosted backdrop blur and glass cards, plus a full-screen landscape lyrics layout.

---

## Tech Stack
- **Language**: Kotlin
- **UI**: Jetpack Compose & Material 3
- **Media & Audio**: AndroidX Media3 (`ExoPlayer`, `MediaLibraryService`, `BaseAudioProcessor`)
- **Compatibility**: Android 8.0 (`API 26`) and above

---

## Related Projects & Credits
- **Original Desktop Version**: [NanoTune for PC](https://github.com/mirakwgnr/nanotune)
- **Created by**: [@mirakwgnr](https://github.com/mirakwgnr)
