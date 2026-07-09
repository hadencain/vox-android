# Vox Android — on-device dictation (Play Store)

Android port of desktop Vox (`src/vox`). Floating bubble → mic → whisper.cpp (JNI) →
MediaPipe Gemma cleanup → AccessibilityService injection. 100% on-device after first-run
model download. Spec: `docs/specs/2026-07-09-vox-android-design.md`.

## Rules
- NEVER use worktree isolation — native builds break in worktrees. Work in place.
- minSdk 33, arm64-v8a only, device floor 6GB RAM.
- Test device: Samsung S24 Ultra via adb. Build passes ≠ done — every feature needs an
  on-device confirmation from the user.
- Pipeline logic/prompts port from `src/vox/dictation/` — keep the same narrow interfaces.
- Models are gitignored (`*.bin`, `*.task`); sideload via `adb push` during development.

## Build
./gradlew assembleDebug && ./gradlew installDebug
Unit tests (JVM, pure logic): ./gradlew testDebugUnitTest
