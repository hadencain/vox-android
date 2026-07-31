# Vox for Android — offline voice dictation, on-device

Private, offline speech-to-text for Android. A floating bubble turns any text field
into a dictation target: tap, speak, and Vox transcribes with **whisper.cpp**, cleans
up your speech with an **on-device Gemma LLM** (fillers, punctuation,
self-corrections), and types the result into the focused app through Android's
accessibility APIs. **No cloud, no account, no audio ever leaves the phone.**

Android port of [Vox for Windows](https://github.com/hadencain/vox), sharing the same
pipeline design and the same mascot — the pixel-art CRT with the bouncing ball that
rides your voice.

## Features

- **100% on-device** — Whisper (small, quantized) + Gemma 3 1B run locally; the only
  network use is the one-time model download (~740 MB over Wi-Fi) on first run
- **Floating bubble** — dictate into any app: chat, email, notes, search bars
- **AI cleanup** — removes "um"s, false starts, and self-corrections; adapts tone to
  the app you're typing in (chat vs email vs notes)
- **AI edit mode** — long-press the bubble, speak an instruction ("make this
  shorter"), and it rewrites your selected text
- **Raw mode** — tap the caption mid-take for verbatim output (passwords, exact
  quotes); raw takes are never written to history
- **Voice commands** — "scratch that" cancels a take
- **Custom vocabulary** — bias words for names/jargon plus find-and-replace
  corrections; Vox mines your dictation history and suggests new entries
- **Live captions** — streaming partial transcripts beside the bubble
- **Haptic cues** — ticks on take start/stop/done, buzz on error

## Requirements

- Android 13+ (API 33), arm64-v8a
- ~6 GB RAM recommended (Whisper + Gemma resident while dictating)
- Microphone, display-over-other-apps, and accessibility permissions (the app
  walks you through setup)

## Build

```bash
./gradlew assembleDebug     # build APK
./gradlew installDebug      # build + install via adb
./gradlew testDebugUnitTest # JVM unit tests (pure logic)
```

whisper.cpp is vendored under `third_party/`. Speech models are not in the repo —
the app downloads them on first launch (or sideload to `files/models/` via adb).

## How it works

```
floating bubble → AudioRecord (16 kHz) → whisper.cpp (JNI) → Gemma cleanup
(MediaPipe LLM) → AccessibilityService text injection → your text field
```

The pipeline is a single-threaded state machine (IDLE → RECORDING → PROCESSING);
models lazy-load on first take and unload after idle timeout to give memory back.

## Keywords

Android dictation app · offline speech to text · on-device AI · voice typing ·
whisper.cpp Android · private voice input · speech recognition without internet ·
local LLM text cleanup
