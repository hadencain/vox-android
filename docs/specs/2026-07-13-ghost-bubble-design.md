# Vox Ghost Bubble — Design Spec

**Date:** 2026-07-13
**Status:** Approved (design conversation 2026-07-13)
**Replaces:** the flat tinted-oval bubble (`bubble_bg.xml` + `ImageView` in `BubbleOverlay.kt`)

## What

Replace the floating bubble "dot" with a small animated ghost character — a disembodied
voice — that carries the app's identity. The ghost keeps the existing 7-state color scheme,
adds a per-state facial expression, is fully animated, and its mouth tracks live mic
amplitude while recording.

## Character definition (canonical)

Reference geometry: `docs/specs/assets/ghost-reference.svg` (canonical shape, committed).
The runtime implementation is procedural — all geometry lives in `GhostView.kt` as code,
so the character is fully versioned with the project. If code and SVG ever disagree, the
SVG is the design of record; update it deliberately, never incidentally.

Anatomy (112-unit viewBox, scales to the 56dp bubble):
- **Body:** dome top, tapered sides, **asymmetric 3-wave hem** (waves differ in width/depth
  and drift horizontally when animated).
- **Face (always present):** two tall oval white eyes with dark pupils, plus a mouth.
  The mouth is the product metaphor (voice) and is never removed in any state.
- **Outline:** white, ~4 units, 90% opacity, drawn in every state so the ghost stays
  visible over any app background, light or dark.

### Uniqueness constraints (hard rules — trademark differentiation)

Checked 2026-07-13 against the known ghost-mascot field. These constraints are what keep
the character distinct; do not drift on them:

| Comparable | Their signature | Our required difference |
|---|---|---|
| Snapchat (Ghostface Chillah) | White, **faceless**, two-point flicked hem, on yellow | Always a full face; wavy hem, no flick; never render white-bodied on yellow |
| Ghostery | Flat light-blue **silhouette**, faceless | Never a flat silhouette: outline + face always drawn; idle blue is deep indigo #3D5AFE |
| Pac-Man ghosts | Uniform scalloped hem, two big eyes, **no mouth**, arcade brights | Asymmetric non-uniform hem; mouth always present |

Additional standing rules: eyes are whites-with-pupils (never solid cutout holes), and the
character is animated/voice-reactive in product — none of the comparables are.

## States

State enum unchanged (`BubbleState`). Tint colors unchanged. Expression added:

| State | Tint | Expression |
|---|---|---|
| IDLE | #3D5AFE | Soft eyes, periodic blink, small mouth; gentle bob |
| WAKING | #FFB300 | Eyes widen, alert pop-in |
| RECORDING | #E53935 | Wide eyes; mouth openness tracks mic RMS |
| RECORDING_RAW | #8E24AA | Same as RECORDING plus flat/serious brows |
| PROCESSING | #00897B | Eyes closed (concentrating), tiny mouth, subtle wobble |
| ERROR | #616161 | X eyes, frown, droop |
| DISABLED | #424242 | Sleepy closed eyes, near-flat mouth, slow bob, "zz" |

State transitions animate (~150ms): color lerp + expression morph.

## Architecture

- **`ui/GhostView.kt` (new):** custom `View`, procedural Canvas drawing. Frame loop
  (Choreographer/ValueAnimator) runs only while attached and visible; drives bob, blink,
  hem-wave phase, and mouth openness (smoothly lerped toward the latest RMS target).
  Public surface: `setState(BubbleState)`, `setAudioLevel(rms: Float)`.
  Expression parameters per state are pure data (unit-testable mapping).
- **`BubbleOverlay.kt`:** swap `ImageView` → `GhostView`; `setState()` forwards to the
  view; add `setAudioLevel(Float)`. Drag / tap / long-press / close-target logic untouched.
- **Amplitude plumbing:** `AudioCapture` gains optional `onLevel: (Float) -> Unit`, fired
  per 100ms chunk with the RMS already computed for silence detection (no extra math in
  the capture loop). `Pipeline` forwards → `VoxService` wires to overlay. No behavior
  change to `SilenceDetector`.
- 100ms RMS cadence is smoothed in `GhostView` (lerp toward target each frame), so the
  mouth animates continuously despite the coarse update rate.

## Error handling

- No RMS events (e.g. raw-tap path or capture failure) → mouth falls back to a gentle
  canned flap in recording states; never frozen open.
- Frame loop must stop when the overlay is hidden (no background drawing/battery drain).

## Testing

- JVM unit tests: state → expression-parameter mapping; RMS → mouth-openness smoothing.
- Visuals and overlay perf: on-device confirmation on the S24 Ultra (per project rule —
  build passing is not done).

## Out of scope (possible follow-ups)

- Re-theming the launcher icon from the mic to the ghost.
- Speech-synced blinking or caption-aware expressions.
