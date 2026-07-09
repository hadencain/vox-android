# Vox Android — On-Device Dictation for the Play Store

**Date:** 2026-07-09
**Status:** Approved design, pre-plan
**Parent project:** Vox (Windows desktop, `src/vox`) — this is a standalone Android port,
not a LAN client. It will live as its own project/repo (suggested: `src/mobile/vox-android`),
following Ship's one-repo-per-project convention.

## Product definition

A fully on-device Android dictation app, published publicly on the Google Play Store.

- **Wedge:** privacy/offline-first — "100% on-device, nothing ever leaves your phone."
  No ads, no analytics, no network calls after initial model download. Competes with
  Gboard voice typing on trust, plus cleanup quality Gboard doesn't have.
- **Monetization:** free for v1; decide later. No ad SDK, no Play Billing — anything that
  phones home undermines the wedge.
- **Device floor:** hard minimum spec gated at first launch. The enforced check is total
  RAM (~6GB+); chipset generation is guidance for store-listing targeting, not a runtime
  check. No degraded tier — everyone who can install it gets the full pipeline.
- **Entry point:** a persistent, draggable floating bubble (chat-head pattern). Tap it and
  Vox types into whatever field is focused in the foreground app, as if it were the
  keyboard. Not an IME replacement; not app-open-then-record.
- **Platform:** Android only, API 33+. Kotlin + JNI/C++. No iOS.

## Engine choice (decided)

**whisper.cpp for ASR + MediaPipe LLM Inference API (quantized Gemma 1B–2B) for cleanup.**

Rationale: vessel (`src/mobile/vessel`) already proves the MediaPipe + JNI/C++ toolchain
on this exact stack and test device. MediaPipe's LLM Inference has a Kotlin-native
surface, Google-tuned GPU delegates across Android hardware, and avoids hand-rolling a
second native inference pipeline (llama.cpp's Android Vulkan backend is less mature).

Accepted cost: the cleanup model family changes from Llama (desktop uses
`llama3.2:3b` via Ollama) to Gemma. The desktop cleanup prompt does **not** transfer 1:1
— a re-tuning pass on the system prompt is planned, bounded work.

Rejected alternative: whisper.cpp + llama.cpp (both GGUF via JNI) for exact desktop
parity. Rejected because two custom native pipelines is more risk than retuning one
prompt, especially with AI-edit mode in v1 scope.

## v1 feature scope (decided)

**In:**
- Core loop: record → Whisper ASR (streaming partials) → LLM cleanup → inject
- Context-aware cleanup (foreground app package → category → cleanup style)
- Custom dictionary (bias words into ASR prompt + post-cleanup find/replace corrections)
- Raw/verbatim mode (skip cleanup for a take)
- Local dictation history
- "Scratch that" whole-utterance cancel
- **AI-edit mode** (read current selection via accessibility tree, speak an instruction,
  rewrite in place)

**Out (v2+):** history mining, richer voice commands ("new line", "backtrack"),
multi-language tuning, IME-mode, any cloud anything.

## Architecture

```
Floating bubble (WindowManager overlay, SYSTEM_ALERT_WINDOW)
      │ tap / long-press
VoxForegroundService  ── owns the state machine, keeps models warm
      │
      ├── AudioRecord (16kHz mono ring buffer) → Silero VAD segmenter
      │         │
      │         ▼
      ├── WhisperBridge (JNI → whisper.cpp)  ── partial + final transcript
      │         │
      │         ▼ (on tap-again / release)
      ├── Commands ("scratch that" cancel check on raw transcript)
      ├── Dictionary (bias words into ASR prompt; post-cleanup corrections)
      ├── Context (foreground package name → category, from AccessibilityService)
      ├── CleanupEngine (MediaPipe LLM Inference, Gemma) ── or skipped in raw mode
      │         │
      │         ▼
      └── VoxAccessibilityService ── finds focused node in foreground app,
                injects via ACTION_SET_TEXT (fallback: clipboard + ACTION_PASTE)
```

- Each stage keeps the desktop's narrow-interface discipline
  (`asr.transcribe(audio) -> str`, `cleanup.clean(text, ctx) -> str`, …) so engines are
  swappable — mirrors `dictation/` module boundaries, re-homed to Kotlin/JNI.
- `VoxAccessibilityService` does double duty: injection target-finding **and**
  foreground-app detection for context-aware cleanup (both need the same a11y tree).
- **AI-edit path:** same bubble, triggered by **long-press** (tap = dictate, long-press =
  AI-edit; both remappable later). The service reads the current selection
  directly off the focused `AccessibilityNodeInfo` (Android exposes selection natively —
  cleaner than desktop's Ctrl+C clipboard round-trip). Speak an instruction → rewrite
  prompt through CleanupEngine → injection replaces the original selection.

## State machine & data flow

```
IDLE ──(bubble tap)──► RECORDING ──(tap again / silence timeout)──► PROCESSING ──► INJECTING ──► IDLE
```

- **IDLE:** bubble docked where last dragged. Models in memory if within the keep-warm
  window; otherwise the next tap shows a visible "waking up" state before recording.
- **RECORDING:** AudioRecord streams to the VAD segmenter; WhisperBridge runs
  rolling-window transcription (same approach as desktop streaming ASR). Partials render
  in a small caption bubble anchored near the floating icon — scaled-down analog of the
  desktop overlay, not full-screen.
- **PROCESSING** (on stop): raw transcript checked against Commands first
  (whole-utterance cancel → IDLE, nothing typed). Otherwise: dictionary corrections,
  Context category attached, CleanupEngine runs unless raw mode is on for this take.
- **INJECTING:** service re-resolves the focused node (it may have changed since the
  take started), sets text via `ACTION_SET_TEXT`. No editable node focused → clipboard
  fallback + toast. Never silently drop a transcript.
- **AI-edit branch:** selection is read and held at trigger-time; PROCESSING runs the
  rewrite prompt instead of cleanup; INJECTING replaces the original selection range.
- **Model lifecycle:** WhisperBridge and CleanupEngine stay loaded while the foreground
  service is alive and recently used (mirrors desktop `ollama_keep_alive`), unloading
  after ~10 min idle to return RAM on floor-spec devices. Reload cost is paid as the
  "waking up" state, not per-request.

## Model delivery (decided)

**First-run download, then fully offline.** APK stays small; on first launch, download
Whisper + Gemma weights (~1–2GB combined) over Wi-Fi with a clear prompt, visible
progress, and explicit size/storage copy. Downloads are resumable (range requests, not
restart-from-zero). Requires hosting the model files (e.g. a Hugging Face repo or a
release bucket). After download: zero network calls, ever.

Rejected alternative: bundling via Play Asset Delivery — viable on size, but a huge
store-listing download and every model swap becomes a full app update.

## Permissions & first-run onboarding

Sequenced by friction, cheapest first:

1. **Device capability gate** — check total RAM against the floor before anything else.
   Below floor: clear "Vox needs ~6GB+ RAM for on-device cleanup" screen, stop.
2. **Microphone** — standard runtime permission dialog.
3. **Model download** — as above. App is unusable until complete, but not misleading.
4. **"Draw over other apps"** (`SYSTEM_ALERT_WINDOW`) — settings deep-link (no in-app
   dialog possible), needed for the bubble.
5. **Accessibility Service enable** — highest-friction ask; settings deep-link plus
   Android's own generic warning. The pre-link screen explains *why* in plain terms
   (this is what lets Vox type for you — the same category of access any keyboard has).

Steps 4–5 need a granted-state detection loop (`onResume` check or poll) since settings
gives no callback. The model download runs **concurrently** with the user handling 4–5 —
onboarding is not strictly serial.

**Play review risk (accepted):** AccessibilityService use for non-disability purposes
requires a permitted-use declaration in Play Console (sometimes a demo video), and weak
declarations get rejected. Hands-free text entry is a defensible accessibility use, but
this is a real go/no-go gate — the declaration gets dry-run before code is "done"
(see Testing).

## Error handling

- **A11y service revoked mid-use:** check service state before *each* injection, not
  just at startup. Revoked → distinct "disabled" bubble state; tapping re-explains and
  deep-links to settings. No silent failure to type.
- **No focused editable field at inject-time:** clipboard fallback + toast. AI-edit's
  "selection gone by inject-time" is treated as the same no-target case.
- **Download interrupted:** resumable; retry affordance on next launch.
- **Inference timeout/failure** (thermal, OOM, native crash): both ASR and cleanup are
  timeout-bounded. Cleanup timeout → inject the **raw transcript** rather than hang or
  lose the take. ASR failure → fail loud (bubble error state). A failed take must never
  look like a successful one (desktop principle, carried over).
- **RAM-pressure kill of the foreground service:** on restart, models reload lazily
  ("waking up" state) — never assume warm.
- **Password/secure fields:** a11y APIs block flagged secure fields by design. Detect
  `isPassword` and refuse with a clear "can't type into secure fields" message instead
  of a silent `ACTION_SET_TEXT` failure. (Mobile analog of desktop's
  SendInput-into-elevated-windows limitation.)

## Testing

- **Real-device mandatory** — same rule as desktop: build-passes ≠ done. Emulators can't
  validate mic quality, a11y injection into third-party apps, or inference
  latency/thermals.
- **Two physical devices minimum:** S24 Ultra (flagship ceiling) + one device near the
  6–8GB floor. The floor device is what validates the floor is real, not theoretical.
- **Injection compatibility matrix:** hand-test against real target apps with different
  text-field implementations — stock Messages, Gmail, a Compose-based app, a
  WebView-based input (mobile browser address bar). Expect at least one app class where
  `ACTION_SET_TEXT` misbehaves and the clipboard fallback carries it.
- **Pure-logic unit tests (no device):** Commands cancel matching, Context
  package→category mapping, Dictionary correction application.
- **Latency/battery pass on the floor device:** cold-start (model load) and warm-path
  timing for ASR and cleanup; rough battery-drain check for an all-day resident
  foreground service.
- **Play Console pre-submission:** dry-run the Accessibility permitted-use declaration
  (and demo video if required) *before* declaring the build done — it's a shipping
  go/no-go gate, not paperwork.

## Risks

| Risk | Mitigation |
|------|------------|
| Play rejects the a11y permitted-use declaration | Defensible hands-free-input framing; dry-run declaration early; fallback architecture (clipboard-copy bubble) exists if forced |
| Gemma cleanup quality below desktop's llama3.2:3b | Bounded prompt re-tuning pass; raw-transcript fallback means worst case is Gboard-parity, not broken |
| `ACTION_SET_TEXT` flaky across app implementations | Compatibility matrix in testing; clipboard+paste fallback path |
| 1–2GB first-run download abandonment | Wi-Fi prompt, resumable download, honest copy; runs concurrent with permission setup |
| Floor device performs worse than expected | Floor device is a first-class test target from the start, not an afterthought |
| Battery drain from resident service | Idle model unload (~10 min); measured in testing, not assumed |
