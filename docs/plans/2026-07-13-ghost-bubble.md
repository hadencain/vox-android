# Ghost Bubble Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat tinted-oval overlay bubble with a procedural animated ghost character whose mouth tracks live mic amplitude while recording.

**Architecture:** A new custom `GhostView` (Canvas drawing + frame loop) replaces the `ImageView` inside `BubbleOverlay`. Pure-logic pieces — state→expression mapping (`GhostFace.kt`) and mouth smoothing/fallback (`MouthDriver.kt`) — live in separate files so they're JVM unit-testable. Mic RMS (already computed for silence detection) is exposed via a new `onLevel` callback on `AudioCapture` and wired through `Pipeline` → `VoxService.bubble`.

**Tech Stack:** Kotlin, Android View/Canvas, Choreographer-style frame loop via `postOnAnimation`, JVM unit tests (`./gradlew testDebugUnitTest`).

## Global Constraints

- Spec of record: `docs/specs/2026-07-13-ghost-bubble-design.md`; canonical shape: `docs/specs/assets/ghost-reference.svg`. Geometry is authored in a 112-unit viewBox and scaled to view size.
- Uniqueness hard rules (trademark): face (eyes + mouth) drawn in EVERY state; asymmetric wavy hem (never uniform scallops, never a two-point flick); white ~4-unit outline at 90% opacity in every state (never a flat silhouette); eyes are whites-with-pupils, never solid cutout holes.
- State tint colors are unchanged from the current bubble: IDLE #3D5AFE, WAKING #FFB300, RECORDING #E53935, RECORDING_RAW #8E24AA, PROCESSING #00897B, ERROR #616161, DISABLED #424242.
- Frame loop must run ONLY while the view is attached and visible (no background drawing).
- No RMS events while in a recording state → mouth falls back to a gentle canned flap; never frozen open.
- Use `0xFF......toInt()` color literals in pure-logic files, NOT `Color.parseColor` (JVM unit tests can't call android.jar methods).
- minSdk 33, arm64-v8a. Build: `./gradlew assembleDebug`. Unit tests: `./gradlew testDebugUnitTest`. Build passing ≠ done — Task 6 is an on-device user gate.
- NEVER use worktree isolation — work in place.

---

### Task 1: GhostFace — state → expression mapping (pure logic)

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/ui/GhostFace.kt`
- Test: `app/src/test/java/com/hadencain/vox/ui/GhostFaceTest.kt`

**Interfaces:**
- Consumes: `BubbleState` enum (exists in `app/src/main/java/com/hadencain/vox/ui/BubbleOverlay.kt`).
- Produces: `data class GhostExpression(tint: Int, eyes: EyeStyle, mouth: MouthStyle, mouthFloor: Float, brows: Boolean, zzz: Boolean, bobPeriodMs: Int, wobble: Boolean)`; `enum class EyeStyle { NORMAL, WIDE, CLOSED, X }`; `enum class MouthStyle { FIXED, TRACK_LEVEL, FROWN }`; `object GhostExpressions { fun forState(state: BubbleState): GhostExpression }`. Task 3 consumes all of these.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class GhostFaceTest {
    @Test fun `every state has an expression and keeps the legacy tint`() {
        val tints = mapOf(
            BubbleState.IDLE to 0xFF3D5AFE.toInt(),
            BubbleState.WAKING to 0xFFFFB300.toInt(),
            BubbleState.RECORDING to 0xFFE53935.toInt(),
            BubbleState.RECORDING_RAW to 0xFF8E24AA.toInt(),
            BubbleState.PROCESSING to 0xFF00897B.toInt(),
            BubbleState.ERROR to 0xFF616161.toInt(),
            BubbleState.DISABLED to 0xFF424242.toInt(),
        )
        for (state in BubbleState.entries) {
            val e = GhostExpressions.forState(state)
            assertEquals("tint for $state", tints[state], e.tint)
        }
    }

    @Test fun `recording states track level, others do not`() {
        assertEquals(MouthStyle.TRACK_LEVEL, GhostExpressions.forState(BubbleState.RECORDING).mouth)
        assertEquals(MouthStyle.TRACK_LEVEL, GhostExpressions.forState(BubbleState.RECORDING_RAW).mouth)
        for (s in listOf(BubbleState.IDLE, BubbleState.WAKING, BubbleState.PROCESSING, BubbleState.DISABLED))
            assertNotEquals("$s must not track level", MouthStyle.TRACK_LEVEL, GhostExpressions.forState(s).mouth)
        assertEquals(MouthStyle.FROWN, GhostExpressions.forState(BubbleState.ERROR).mouth)
    }

    @Test fun `raw recording is distinguished by brows`() {
        assertTrue(GhostExpressions.forState(BubbleState.RECORDING_RAW).brows)
        assertFalse(GhostExpressions.forState(BubbleState.RECORDING).brows)
    }

    @Test fun `disabled sleeps`() {
        val e = GhostExpressions.forState(BubbleState.DISABLED)
        assertEquals(EyeStyle.CLOSED, e.eyes)
        assertTrue(e.zzz)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.GhostFaceTest"`
Expected: FAIL to compile — `GhostExpressions` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.hadencain.vox.ui

enum class EyeStyle { NORMAL, WIDE, CLOSED, X }
enum class MouthStyle { FIXED, TRACK_LEVEL, FROWN }

/** Pure expression parameters per pipeline state. mouthFloor is the resting openness
 *  (0..1) for FIXED mouths, and the minimum openness while TRACK_LEVEL. */
data class GhostExpression(
    val tint: Int,
    val eyes: EyeStyle,
    val mouth: MouthStyle,
    val mouthFloor: Float,
    val brows: Boolean = false,
    val zzz: Boolean = false,
    val bobPeriodMs: Int = 2400,
    val wobble: Boolean = false,
)

object GhostExpressions {
    fun forState(state: BubbleState): GhostExpression = when (state) {
        BubbleState.IDLE          -> GhostExpression(0xFF3D5AFE.toInt(), EyeStyle.NORMAL, MouthStyle.FIXED, 0.15f)
        BubbleState.WAKING        -> GhostExpression(0xFFFFB300.toInt(), EyeStyle.WIDE, MouthStyle.FIXED, 0.35f, bobPeriodMs = 1200)
        BubbleState.RECORDING     -> GhostExpression(0xFFE53935.toInt(), EyeStyle.WIDE, MouthStyle.TRACK_LEVEL, 0.1f)
        BubbleState.RECORDING_RAW -> GhostExpression(0xFF8E24AA.toInt(), EyeStyle.WIDE, MouthStyle.TRACK_LEVEL, 0.1f, brows = true)
        BubbleState.PROCESSING    -> GhostExpression(0xFF00897B.toInt(), EyeStyle.CLOSED, MouthStyle.FIXED, 0.1f, wobble = true)
        BubbleState.ERROR         -> GhostExpression(0xFF616161.toInt(), EyeStyle.X, MouthStyle.FROWN, 0f, bobPeriodMs = 3600)
        BubbleState.DISABLED      -> GhostExpression(0xFF424242.toInt(), EyeStyle.CLOSED, MouthStyle.FIXED, 0.05f, zzz = true, bobPeriodMs = 4000)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.GhostFaceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/ui/GhostFace.kt app/src/test/java/com/hadencain/vox/ui/GhostFaceTest.kt
git commit -m "Ghost bubble: state -> expression mapping (pure logic)"
```

---

### Task 2: MouthDriver — RMS smoothing + canned-flap fallback (pure logic)

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/ui/MouthDriver.kt`
- Test: `app/src/test/java/com/hadencain/vox/ui/MouthDriverTest.kt`

**Interfaces:**
- Consumes: nothing project-specific.
- Produces: `class MouthDriver(fallbackAfterMs: Long = 600)` with `fun onLevel(rms: Float, nowMs: Long)` (thread-safe, called from the audio thread) and `fun openness(nowMs: Long, dtMs: Long, tracking: Boolean, floor: Float): Float` (called once per frame from the UI thread, returns 0..1). Task 3 consumes both.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class MouthDriverTest {
    /** Run the per-frame update n times at 16ms steps and return the last value. */
    private fun settle(d: MouthDriver, startMs: Long, frames: Int, tracking: Boolean, floor: Float): Float {
        var v = 0f; var t = startMs
        repeat(frames) { t += 16; v = d.openness(t, 16, tracking, floor) }
        return v
    }

    @Test fun `openness converges toward a loud level`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)  // loud speech
        val v = settle(d, 1000, 30, tracking = true, floor = 0.1f)
        assertTrue("expected wide open, got $v", v > 0.7f)
    }

    @Test fun `silence between words closes toward the floor`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)
        settle(d, 1000, 30, tracking = true, floor = 0.1f)
        d.onLevel(0.0f, 1500)  // fresh level event: silence
        val v = settle(d, 1500, 60, tracking = true, floor = 0.1f)
        assertTrue("expected near floor, got $v", v < 0.2f)
    }

    @Test fun `no level events falls back to canned flap - never frozen`() {
        val d = MouthDriver(fallbackAfterMs = 600)
        // No onLevel ever called; tracking. Collect samples over 2s.
        val samples = mutableListOf<Float>()
        var t = 5000L
        repeat(120) { t += 16; samples.add(d.openness(t, 16, tracking = true, floor = 0.1f)) }
        val late = samples.takeLast(60)
        assertTrue("flap must move", late.max() - late.min() > 0.05f)
        assertTrue("flap stays gentle", late.max() < 0.7f)
        assertTrue("never below floor-ish", late.min() > 0.02f)
    }

    @Test fun `not tracking returns the floor`() {
        val d = MouthDriver()
        d.onLevel(0.08f, 1000)  // stale loud level must be ignored
        val v = settle(d, 1000, 60, tracking = false, floor = 0.15f)
        assertEquals(0.15f, v, 0.03f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.MouthDriverTest"`
Expected: FAIL to compile — `MouthDriver` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.hadencain.vox.ui

import kotlin.math.exp
import kotlin.math.sin

/** Smooths coarse (100ms) mic RMS into a continuous mouth-openness value.
 *  onLevel is called from the audio capture thread; openness from the UI frame loop.
 *  If no level event arrives for [fallbackAfterMs] while tracking, falls back to a
 *  gentle canned flap so the mouth is never frozen (spec: error handling). */
class MouthDriver(private val fallbackAfterMs: Long = 600) {
    // Typical speech RMS on VOICE_RECOGNITION source is ~0.02..0.1; 0.08 maps to fully open.
    private val fullOpenRms = 0.08f

    @Volatile private var targetLevel = 0f
    @Volatile private var lastLevelMs = Long.MIN_VALUE
    private var current = 0f  // UI-thread only

    fun onLevel(rms: Float, nowMs: Long) {
        targetLevel = (rms / fullOpenRms).coerceIn(0f, 1f)
        lastLevelMs = nowMs
    }

    fun openness(nowMs: Long, dtMs: Long, tracking: Boolean, floor: Float): Float {
        val target = when {
            !tracking -> floor
            lastLevelMs == Long.MIN_VALUE || nowMs - lastLevelMs > fallbackAfterMs ->
                // canned flap: 0..0.4 above floor on a ~0.9s cycle
                floor + 0.4f * (0.5f + 0.5f * sin(nowMs / 140.0).toFloat())
            else -> floor + targetLevel * (1f - floor)
        }
        // Exponential approach with ~60ms time constant -- smooth at any frame rate.
        val k = 1f - exp(-dtMs / 60.0).toFloat()
        current += (target - current) * k
        return current.coerceIn(0f, 1f)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.MouthDriverTest"`
Expected: PASS (4 tests). If the canned-flap bounds fail, tune the flap amplitude in the implementation, not the test's intent (must move, must stay gentle).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/ui/MouthDriver.kt app/src/test/java/com/hadencain/vox/ui/MouthDriverTest.kt
git commit -m "Ghost bubble: mouth driver (RMS smoothing + canned-flap fallback)"
```

---

### Task 3: GhostView — procedural drawing + frame loop

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/ui/GhostView.kt`

**Interfaces:**
- Consumes: `GhostExpressions.forState(BubbleState): GhostExpression`, `EyeStyle`, `MouthStyle` (Task 1); `MouthDriver.onLevel(Float, Long)` / `MouthDriver.openness(Long, Long, Boolean, Float)` (Task 2).
- Produces: `class GhostView(context: Context) : View` with `fun setGhostState(state: BubbleState)` (UI thread) and `fun setAudioLevel(rms: Float)` (any thread). Task 4 consumes both.

No JVM test — this is Canvas drawing; verified by build (this task) and on device (Task 6). All tunable geometry is in 112-unit space per the reference SVG.

- [ ] **Step 1: Write the implementation**

```kotlin
package com.hadencain.vox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** Procedural ghost character. Geometry is authored in a 112-unit viewBox
 *  (docs/specs/assets/ghost-reference.svg is the design of record) and scaled to the
 *  view size. Frame loop runs only while attached AND visible.
 *
 *  Uniqueness hard rules (spec): face drawn in every state; asymmetric wavy hem;
 *  white outline in every state; eyes are whites-with-pupils. Do not drift. */
class GhostView(context: Context) : View(context) {
    private var expression = GhostExpressions.forState(BubbleState.IDLE)
    private val mouthDriver = MouthDriver()

    private var currentTint = expression.tint
    private var lastFrameMs = 0L
    private var nextBlinkMs = 0L
    private var blinkStartMs = -1L
    private var running = false

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)  // ~90% white, every state (spec hard rule)
        strokeJoin = Paint.Join.ROUND
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A2E.toInt() }
    private val strokeDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF1A1A2E.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val strokeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND
    }
    private val zzzPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255); isFakeBoldText = true
    }
    private val bodyPath = Path()

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setGhostState(state: BubbleState) {
        expression = GhostExpressions.forState(state)
        invalidate()
    }

    /** Called from the audio capture thread; MouthDriver fields are @Volatile. */
    fun setAudioLevel(rms: Float) = mouthDriver.onLevel(rms, SystemClock.uptimeMillis())

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE
        if (shouldRun && !running) { running = true; postOnAnimation(frameTick) }
        else if (!shouldRun) running = false
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 16 else (now - lastFrameMs).coerceIn(1, 100)
        lastFrameMs = now
        val s = width / 112f
        val e = expression

        // Tint lerp toward the state color (~150ms transition per spec).
        val k = 1f - Math.exp(-dt / 60.0).toFloat()
        currentTint = lerpColor(currentTint, e.tint, k)
        bodyPaint.color = currentTint
        outlinePaint.strokeWidth = 4f * s
        strokeDark.strokeWidth = 3.4f * s
        strokeWhite.strokeWidth = 3f * s
        zzzPaint.textSize = 13f * s

        // Idle bob + optional processing wobble.
        val bob = 2f * s * sin(2.0 * PI * (now % e.bobPeriodMs) / e.bobPeriodMs).toFloat()
        canvas.save()
        canvas.translate(0f, bob)
        if (e.wobble) canvas.rotate(3f * sin(now / 180.0).toFloat(), width / 2f, height / 2f)

        drawBody(canvas, s, now)
        drawEyes(canvas, s, now, e)
        if (e.brows) {
            canvas.drawLine(37f*s, 35f*s, 51f*s, 37f*s, strokeWhite)
            canvas.drawLine(75f*s, 35f*s, 61f*s, 37f*s, strokeWhite)
        }
        drawMouth(canvas, s, now, dt, e)
        if (e.zzz) canvas.drawText("zZ", 84f * s, 26f * s, zzzPaint)
        canvas.restore()
    }

    /** Asymmetric 3-wave hem, phase-animated. Matches ghost-reference.svg at phase 0. */
    private fun drawBody(canvas: Canvas, s: Float, now: Long) {
        val ph = (now / 260.0).toFloat()
        fun hy(base: Float, amp: Float, off: Float) = (base + amp * sin(ph + off)) * s
        bodyPath.rewind()
        bodyPath.moveTo(56f*s, 14f*s)
        bodyPath.cubicTo(34f*s, 14f*s, 22f*s, 30f*s, 22f*s, 52f*s)
        bodyPath.lineTo(22f*s, hy(82f, 1.5f, 0f))
        bodyPath.quadTo(27f*s, hy(74f, 2.5f, 1.1f), 33f*s, hy(82f, 1.5f, 2.3f))
        bodyPath.quadTo(40f*s, hy(92f, 2.5f, 3.1f), 48f*s, hy(83f, 1.5f, 4.2f))
        bodyPath.quadTo(56f*s, hy(74f, 2.5f, 5.0f), 65f*s, hy(84f, 1.5f, 0.7f))
        bodyPath.quadTo(74f*s, hy(93f, 2.5f, 1.9f), 79f*s, hy(83f, 1.5f, 2.8f))
        bodyPath.quadTo(84f*s, hy(74f, 2.5f, 3.9f), 90f*s, hy(80f, 1.5f, 5.3f))
        bodyPath.lineTo(90f*s, 52f*s)
        bodyPath.cubicTo(90f*s, 30f*s, 78f*s, 14f*s, 56f*s, 14f*s)
        bodyPath.close()
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, outlinePaint)
    }

    private fun drawEyes(canvas: Canvas, s: Float, now: Long, e: GhostExpression) {
        val y = 48f * s
        when (e.eyes) {
            EyeStyle.CLOSED -> {
                canvas.drawArcEye(38f*s, y, 50f*s, s, strokeWhite)
                canvas.drawArcEye(62f*s, y, 74f*s, s, strokeWhite)
            }
            EyeStyle.X -> { drawX(canvas, 44f*s, y, 5f*s); drawX(canvas, 68f*s, y, 5f*s) }
            EyeStyle.NORMAL, EyeStyle.WIDE -> {
                val ry = (if (e.eyes == EyeStyle.WIDE) 11f else 9f) * s * blinkScale(now)
                val pr = (if (e.eyes == EyeStyle.WIDE) 4f else 3.2f) * s
                for (cx in floatArrayOf(44f*s, 68f*s)) {
                    canvas.drawOval(cx - 6.5f*s, y - ry, cx + 6.5f*s, y + ry, whitePaint)
                    if (ry > 3f * s) canvas.drawCircle(cx + 1.5f*s, y + 2f*s, pr, darkPaint)
                }
            }
        }
    }

    /** Periodic blink: every 2.8-4s (deterministic jitter), 150ms squeeze to near-closed. */
    private fun blinkScale(now: Long): Float {
        if (blinkStartMs < 0 && now >= nextBlinkMs) blinkStartMs = now
        if (blinkStartMs >= 0) {
            val t = (now - blinkStartMs) / 150f
            if (t >= 1f) {
                blinkStartMs = -1
                nextBlinkMs = now + 2800 + (now % 1200)  // deterministic jitter, no Random needed
            } else return 1f - 0.9f * sin(PI * t.toDouble()).toFloat()
        }
        return 1f
    }

    private fun drawX(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokeWhite)
        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, strokeWhite)
    }

    private fun drawMouth(canvas: Canvas, s: Float, now: Long, dt: Long, e: GhostExpression) {
        if (e.mouth == MouthStyle.FROWN) {
            val p = Path().apply { moveTo(48f*s, 70f*s); quadTo(56f*s, 63f*s, 64f*s, 70f*s) }
            canvas.drawPath(p, strokeDark)
            return
        }
        val open = mouthDriver.openness(now, dt, e.mouth == MouthStyle.TRACK_LEVEL, e.mouthFloor)
        val rx = (5f + open * 3.5f) * s
        val ry = (1.5f + open * 8f) * s
        val cy = (66f + open * 2f) * s
        canvas.drawOval(56f*s - rx, cy - ry, 56f*s + rx, cy + ry, darkPaint)
    }

    private fun lerpColor(from: Int, to: Int, k: Float): Int {
        fun ch(a: Int, b: Int) = (a + ((b - a) * k)).toInt()
        return Color.argb(255,
            ch(Color.red(from), Color.red(to)),
            ch(Color.green(from), Color.green(to)),
            ch(Color.blue(from), Color.blue(to)))
    }
}

/** Closed-eye arc: gentle downward curve between x0 and x1 at height y. */
private fun Canvas.drawArcEye(x0: Float, y: Float, x1: Float, s: Float, paint: Paint) {
    val p = Path().apply { moveTo(x0, y); quadTo((x0 + x1) / 2f, y + 5f * s, x1, y) }
    drawPath(p, paint)
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (GhostView isn't referenced anywhere yet — that's Task 4.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/ui/GhostView.kt
git commit -m "Ghost bubble: procedural GhostView (Canvas + frame loop)"
```

---

### Task 4: BubbleOverlay swap — ImageView → GhostView

**Files:**
- Modify: `app/src/main/java/com/hadencain/vox/ui/BubbleOverlay.kt`
- Delete: `app/src/main/res/drawable/bubble_bg.xml`

**Interfaces:**
- Consumes: `GhostView.setGhostState(BubbleState)`, `GhostView.setAudioLevel(Float)` (Task 3).
- Produces: `BubbleOverlay.setAudioLevel(rms: Float)` — thread-safe, callable from the audio thread. Task 5 consumes it. `setState`/`setCaption`/`show`/`hide`/gesture API unchanged.

- [ ] **Step 1: Swap the view**

In `BubbleOverlay.kt` replace the `bubble` field (currently an `ImageView` with `bubble_bg`):

```kotlin
private val bubble = GhostView(context)
```

Remove the now-unused `import android.widget.ImageView`. Replace the body of `setState` (the `bubble.background.setTint(...)` block) with:

```kotlin
fun setState(state: BubbleState) {
    currentState = state
    handler.post { bubble.setGhostState(state) }
}
```

Add below `setCaption`:

```kotlin
/** Mic level passthrough -- called from the audio capture thread; GhostView handles
 *  cross-thread safety internally. */
fun setAudioLevel(rms: Float) = bubble.setAudioLevel(rms)
```

Everything else (drag listener, close target, caption, layout params) stays untouched — the `size` field still drives the WindowManager layout, and `GhostView` draws to whatever size it's given.

- [ ] **Step 2: Delete the dead drawable**

```bash
rm app/src/main/res/drawable/bubble_bg.xml
```

Then confirm nothing else references it: search for `bubble_bg` across `app/src` — expect zero hits.

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A app/src/main/java/com/hadencain/vox/ui/BubbleOverlay.kt app/src/main/res/drawable
git commit -m "Ghost bubble: swap overlay ImageView for GhostView"
```

---

### Task 5: Amplitude plumbing — AudioCapture → Pipeline → bubble

**Files:**
- Modify: `app/src/main/java/com/hadencain/vox/asr/AudioCapture.kt`
- Modify: `app/src/main/java/com/hadencain/vox/Pipeline.kt:141-144` (the `AudioCapture(...)` construction in `handleStartTake`)
- Test: `app/src/test/java/com/hadencain/vox/asr/SilenceDetectorTest.kt` (extend, do not rewrite)

**Interfaces:**
- Consumes: `BubbleOverlay.setAudioLevel(Float)` (Task 4).
- Produces: `SilenceDetector.feedRms(rms: Float, nowMs: Long): Boolean`; `AudioCapture` constructor gains `onLevel: ((Float) -> Unit)? = null`.

- [ ] **Step 1: Write the failing test**

Append to `SilenceDetectorTest.kt` (read the file first and match its existing test style/constructor arguments):

```kotlin
@Test fun `feedRms behaves like feed with the equivalent rms`() {
    val a = SilenceDetector(timeoutMs = 1000, sampleRate = 16000)
    val b = SilenceDetector(timeoutMs = 1000, sampleRate = 16000)
    val loud = FloatArray(1600) { 0.1f }   // rms 0.1 > threshold
    val quiet = FloatArray(1600) { 0.001f } // rms 0.001 < threshold
    assertEquals(a.feed(loud, 0), b.feedRms(0.1f, 0))
    assertEquals(a.feed(quiet, 500), b.feedRms(0.001f, 500))
    assertEquals(a.feed(quiet, 1600), b.feedRms(0.001f, 1600))  // both fire here
    assertEquals(a.feed(loud, 1700), b.feedRms(0.1f, 1700))     // both stay fired=false
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.asr.SilenceDetectorTest"`
Expected: FAIL to compile — `feedRms` unresolved.

- [ ] **Step 3: Refactor SilenceDetector and add the level callback**

In `AudioCapture.kt`, split `feed` so RMS is computed once and shared:

```kotlin
fun feed(chunk: FloatArray, nowMs: Long): Boolean {
    var sum = 0.0
    for (s in chunk) sum += s * s
    return feedRms(sqrt(sum / chunk.size).toFloat(), nowMs)
}

fun feedRms(rms: Float, nowMs: Long): Boolean {
    if (fired) return false
    if (rms >= threshold) { heardSpeech = true; lastSpeechMs = nowMs }
    if (heardSpeech && nowMs - lastSpeechMs >= timeoutMs) { fired = true; return true }
    return false
}
```

Add the constructor parameter:

```kotlin
class AudioCapture(
    private val onSilenceTimeout: () -> Unit,
    private val silenceTimeoutMs: Long,
    private val onLevel: ((Float) -> Unit)? = null,
) {
```

In the capture thread loop, replace the `detector.feed(chunk.copyOf(n), ...)` line with a single shared RMS computation (this also drops the per-chunk `copyOf` allocation):

```kotlin
val n = record?.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING) ?: break
if (n <= 0) continue
synchronized(buffer) { for (i in 0 until n) buffer.add(chunk[i]) }
var sum = 0.0
for (i in 0 until n) sum += chunk[i] * chunk[i]
val rms = sqrt(sum / n).toFloat()
onLevel?.invoke(rms)
if (detector.feedRms(rms, System.currentTimeMillis())) onSilenceTimeout()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.asr.SilenceDetectorTest"`
Expected: PASS — all pre-existing tests plus the new one.

- [ ] **Step 5: Wire it through Pipeline**

In `Pipeline.kt` `handleStartTake`, extend the `AudioCapture` construction:

```kotlin
val newCapture = AudioCapture(
    onSilenceTimeout = { scope.launch(stateDispatcher) { handleStopTake() } },
    silenceTimeoutMs = settings.silenceTimeoutMs,
    onLevel = { service.bubble.setAudioLevel(it) },
)
```

`setAudioLevel` is thread-safe end-to-end (volatile writes in `MouthDriver`), so no dispatcher hop is needed — do NOT wrap it in `scope.launch`.

- [ ] **Step 6: Full build + full unit test run**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: all tests PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/asr/AudioCapture.kt app/src/main/java/com/hadencain/vox/Pipeline.kt app/src/test/java/com/hadencain/vox/asr/SilenceDetectorTest.kt
git commit -m "Ghost bubble: mic RMS tap from AudioCapture to the overlay"
```

---

### Task 6: On-device confirmation (user gate — do not self-close)

**Files:** none (verification only).

- [ ] **Step 1: Install on the S24 Ultra**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, app installed via adb.

- [ ] **Step 2: Hand the checklist to the user**

Per project rule, build passing ≠ done. Report "build passes, needs your test" and ask the user to confirm each of:

1. Ghost renders at bubble size — face readable, outline visible over both a light app (e.g. Settings) and a dark app.
2. IDLE: gentle bob + periodic blink. State colors still glanceable across all 7 states (trigger ERROR by e.g. revoking a11y mid-flow; DISABLED by disabling the accessibility service).
3. RECORDING: mouth visibly follows speech and closes between words; WAKING → RECORDING → PROCESSING → IDLE transitions animate smoothly (~150ms, no snap).
4. RECORDING_RAW (tap caption during a take): brows appear, purple tint.
5. Drag, tap, long-press, and drag-to-close still work exactly as before.
6. No jank/battery concern: leave the bubble idle 10+ minutes, confirm no visible slowdown; confirm the frame loop stops when the bubble is hidden (stop Vox, check no residual GPU/CPU activity attributable to the app).

- [ ] **Step 3: Fix what the user reports, then re-gate**

Any visual tuning (hem wave amplitude, mouth sensitivity `fullOpenRms`, blink cadence) happens here against real-device feedback. Each fix: edit → `./gradlew installDebug` → user re-checks. Only the user closes this task.

---

## Self-Review

- **Spec coverage:** character anatomy + uniqueness rules (Tasks 3 constants + Global Constraints), 7-state tint/expression table (Task 1), amplitude-driven mouth (Tasks 2, 5), ~150ms transitions (Task 3 tint lerp), frame-loop lifecycle (Task 3 `updateRunning`), canned-flap fallback (Task 2), unit tests for mapping + smoothing (Tasks 1, 2), on-device gate (Task 6). Launcher icon re-theme: explicitly out of scope in spec — no task, correct.
- **Placeholders:** none — every code step has complete code.
- **Type consistency:** `GhostExpression.mouthFloor` used consistently; `setGhostState`/`setAudioLevel` names match across Tasks 3-5; `feedRms` matches between Task 5 test and implementation.
