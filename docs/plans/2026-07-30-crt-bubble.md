# CRT Bubble Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ghost bubble character with the Vox CRT — the pixel-art monitor with the Bounce ball screen — matching the desktop app's status widget so both platforms share one brand character.

**Architecture:** Same three-layer split the ghost used: `CrtFace.kt` (pure state→expression map + shared geometry/palette constants, JVM-testable) and `BallSim.kt` (ball physics + RMS smoothing, JVM-testable, port of desktop `BallSim`) feed `CrtView.kt` (Canvas drawing + frame loop, ports desktop `_draw_face`). `BubbleOverlay` swaps `GhostView` for `CrtView`; the ghost files and their tests are deleted (git history keeps them). Reference implementation: `C:/Users/haden/Documents/Ship/src/AI-stuff/vox/dictation/status_widget.py` (NOTE: `src/vox` moved to `src/AI-stuff/vox` on 2026-07-30).

**Tech Stack:** Kotlin, Android View/Canvas, frame loop via `postOnAnimation`, JUnit4 JVM tests.

## Global Constraints

- Geometry is authored on desktop's 24-block grid (widget block-space), scaled to view size (`b = width / 24f`). Chassis rects, screen bounds (`SX=4.5, SY=6.5, SW=13, SH=8.5`, `FLOOR = SY+SH-1.3`), and palette (`#6b4a35` body, `#7d5843` light, `#553a29` dark, `#10141f` glass, `#f5efdf` ball, `#ffb946` amber, `#ff5f56` red, `#242b3d` track) are copied verbatim from `status_widget.py` — do not restyle.
- State mapping (desktop has 4 states; Android's 7 map as): IDLE→drift, WAKING→desktop "loading" (dim 0.7, amber blink lamp, deflated ball + indeterminate sweep bar), RECORDING→ball rides RMS + red blink lamp, RECORDING_RAW→same + amber RAW chin text, PROCESSING→orbit spinner, ERROR→red flatline across the glass + solid red lamp (new, CRT idiom), DISABLED→blank glass, dim 0.55, dim amber lamp (standby).
- Pure-logic files (`CrtFace.kt`, `BallSim.kt`) must have zero `android.*` imports and use `0xFF......toInt()` color literals, NOT `Color.parseColor` (JVM unit tests can't call android.jar methods).
- Frame loop runs ONLY while the view is attached and visible (port `GhostView`'s `updateRunning` pattern verbatim). No per-frame allocation in `onDraw` (reuse Paint objects; all drawing is `drawRect` + one `drawText`).
- RMS normalization keeps `MouthDriver`'s proven constant: `fullOpenRms = 0.08f` (typical speech RMS on VOICE_RECOGNITION is ~0.02..0.1). Level smoothing uses desktop's `dt*18` ease.
- `BubbleOverlay`'s public API (`setState`, `setCaption`, `setAudioLevel`, gestures) must not change — `Pipeline`/`VoxService` are not touched.
- minSdk 33, arm64-v8a. Build: `./gradlew assembleDebug`. Unit tests: `./gradlew testDebugUnitTest`. If JAVA_HOME is unset in Git Bash: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`. Build passing ≠ done — final task is an on-device user gate.
- NEVER use worktree isolation — work in place. Never add Co-Authored-By trailers.

---

### Task 1: CrtFace + BallSim — state map and ball physics (pure logic)

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/ui/CrtFace.kt`
- Create: `app/src/main/java/com/hadencain/vox/ui/BallSim.kt`
- Test: `app/src/test/java/com/hadencain/vox/ui/CrtFaceTest.kt`
- Test: `app/src/test/java/com/hadencain/vox/ui/BallSimTest.kt`

**Interfaces:**
- Consumes: `BubbleState` enum (exists in `app/src/main/java/com/hadencain/vox/ui/BubbleOverlay.kt`).
- Produces (Task 2 consumes all of these): `enum class LampStyle { OFF, RED_BLINK, RED_SOLID, AMBER_BLINK, AMBER_DIM }`; `enum class ScreenStyle { DRIFT, RIDE_LEVEL, ORBIT, SWEEP, FLATLINE, BLANK }`; `data class CrtExpression(screen: ScreenStyle, lamp: LampStyle, dim: Float = 1f, raw: Boolean = false)`; `object CrtExpressions { fun forState(state: BubbleState): CrtExpression }`; `object CrtFace` holding palette const ints (`BODY, BODY_L, BODY_D, GLASS, BALL, AMBER, RED, TRACK`) and geometry const floats (`SX, SY, SW, SH, SX2, SY2, FLOOR`); `class BallSim { val x: Float; val y: Float; val level: Float; fun onLevel(rms: Float); fun step(dt: Float, screen: ScreenStyle) }`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/hadencain/vox/ui/CrtFaceTest.kt`:

```kotlin
package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class CrtFaceTest {
    @Test fun `recording states ride the level, others do not`() {
        assertEquals(ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(BubbleState.RECORDING).screen)
        assertEquals(ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(BubbleState.RECORDING_RAW).screen)
        for (s in listOf(BubbleState.IDLE, BubbleState.WAKING, BubbleState.PROCESSING,
                         BubbleState.ERROR, BubbleState.DISABLED))
            assertNotEquals("$s must not ride level", ScreenStyle.RIDE_LEVEL, CrtExpressions.forState(s).screen)
    }

    @Test fun `raw badge only on raw recording`() {
        assertTrue(CrtExpressions.forState(BubbleState.RECORDING_RAW).raw)
        for (s in BubbleState.entries.filter { it != BubbleState.RECORDING_RAW })
            assertFalse("$s must not show RAW", CrtExpressions.forState(s).raw)
    }

    @Test fun `rec lamp blinks red while recording, error is solid red`() {
        assertEquals(LampStyle.RED_BLINK, CrtExpressions.forState(BubbleState.RECORDING).lamp)
        assertEquals(LampStyle.RED_BLINK, CrtExpressions.forState(BubbleState.RECORDING_RAW).lamp)
        assertEquals(LampStyle.RED_SOLID, CrtExpressions.forState(BubbleState.ERROR).lamp)
        assertEquals(ScreenStyle.FLATLINE, CrtExpressions.forState(BubbleState.ERROR).screen)
    }

    @Test fun `waking dims with amber blink and sweep, disabled is dim standby`() {
        val w = CrtExpressions.forState(BubbleState.WAKING)
        assertEquals(ScreenStyle.SWEEP, w.screen)
        assertEquals(LampStyle.AMBER_BLINK, w.lamp)
        assertEquals(0.7f, w.dim)
        val d = CrtExpressions.forState(BubbleState.DISABLED)
        assertEquals(ScreenStyle.BLANK, d.screen)
        assertEquals(LampStyle.AMBER_DIM, d.lamp)
        assertEquals(0.55f, d.dim)
    }

    @Test fun `idle drifts at full brightness with the lamp off`() {
        val e = CrtExpressions.forState(BubbleState.IDLE)
        assertEquals(ScreenStyle.DRIFT, e.screen)
        assertEquals(LampStyle.OFF, e.lamp)
        assertEquals(1f, e.dim)
    }
}
```

Create `app/src/test/java/com/hadencain/vox/ui/BallSimTest.kt`:

```kotlin
package com.hadencain.vox.ui

import org.junit.Assert.*
import org.junit.Test

class BallSimTest {
    @Test fun `ball stays inside the glass through 2000 drift steps`() {
        val b = BallSim()
        repeat(2000) {
            b.step(0.033f, ScreenStyle.DRIFT)
            assertTrue("x=${b.x}", b.x >= CrtFace.SX && b.x <= CrtFace.SX2)
            assertTrue("y=${b.y}", b.y >= CrtFace.SY && b.y <= CrtFace.SY2)
        }
    }

    @Test fun `loud voice lifts the ball, silence floors it`() {
        val b = BallSim()
        b.onLevel(0.08f)  // fullOpenRms -> level 1.0
        repeat(60) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertTrue("expected lifted, y=${b.y}", b.y < CrtFace.FLOOR - 2f)
        b.onLevel(0f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertTrue("expected floored, y=${b.y}", b.y > CrtFace.FLOOR - 0.5f)
    }

    @Test fun `level normalizes and clamps rms`() {
        val b = BallSim()
        b.onLevel(0.04f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertEquals(0.5f, b.level, 0.05f)
        b.onLevel(9f)
        repeat(120) { b.step(0.033f, ScreenStyle.RIDE_LEVEL) }
        assertEquals(1f, b.level, 0.05f)
    }

    @Test fun `orbit sweep flatline and blank do not move the ball`() {
        val b = BallSim()
        repeat(10) { b.step(0.033f, ScreenStyle.DRIFT) }  // move off spawn
        val x = b.x; val y = b.y
        for (s in listOf(ScreenStyle.ORBIT, ScreenStyle.SWEEP, ScreenStyle.FLATLINE, ScreenStyle.BLANK))
            repeat(30) { b.step(0.033f, s) }
        assertEquals(x, b.x, 1e-6f)
        assertEquals(y, b.y, 1e-6f)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.CrtFaceTest" --tests "com.hadencain.vox.ui.BallSimTest"`
Expected: FAIL to compile — `CrtExpressions`, `BallSim`, `CrtFace` unresolved.

- [ ] **Step 3: Implement CrtFace.kt**

```kotlin
package com.hadencain.vox.ui

enum class LampStyle { OFF, RED_BLINK, RED_SOLID, AMBER_BLINK, AMBER_DIM }
enum class ScreenStyle { DRIFT, RIDE_LEVEL, ORBIT, SWEEP, FLATLINE, BLANK }

/** Pure display parameters per pipeline state. dim multiplies the whole face's opacity
 *  (desktop dims to 0.7 while loading; standby is darker still). */
data class CrtExpression(
    val screen: ScreenStyle,
    val lamp: LampStyle,
    val dim: Float = 1f,
    val raw: Boolean = false,
)

/** Shared palette + screen geometry, copied from desktop status_widget.py. 24-block grid.
 *  0x literals, not Color.parseColor — JVM unit tests can't call android.jar. */
object CrtFace {
    val BODY = 0xFF6B4A35.toInt()
    val BODY_L = 0xFF7D5843.toInt()
    val BODY_D = 0xFF553A29.toInt()
    val GLASS = 0xFF10141F.toInt()
    val BALL = 0xFFF5EFDF.toInt()
    val AMBER = 0xFFFFB946.toInt()
    val RED = 0xFFFF5F56.toInt()
    val TRACK = 0xFF242B3D.toInt()

    const val SX = 4.5f
    const val SY = 6.5f
    const val SW = 13f
    const val SH = 8.5f
    const val SX2 = SX + SW
    const val SY2 = SY + SH
    const val FLOOR = SY2 - 1.3f
}

object CrtExpressions {
    fun forState(state: BubbleState): CrtExpression = when (state) {
        BubbleState.IDLE          -> CrtExpression(ScreenStyle.DRIFT, LampStyle.OFF)
        BubbleState.WAKING        -> CrtExpression(ScreenStyle.SWEEP, LampStyle.AMBER_BLINK, dim = 0.7f)
        BubbleState.RECORDING     -> CrtExpression(ScreenStyle.RIDE_LEVEL, LampStyle.RED_BLINK)
        BubbleState.RECORDING_RAW -> CrtExpression(ScreenStyle.RIDE_LEVEL, LampStyle.RED_BLINK, raw = true)
        BubbleState.PROCESSING    -> CrtExpression(ScreenStyle.ORBIT, LampStyle.OFF)
        BubbleState.ERROR         -> CrtExpression(ScreenStyle.FLATLINE, LampStyle.RED_SOLID)
        BubbleState.DISABLED      -> CrtExpression(ScreenStyle.BLANK, LampStyle.AMBER_DIM, dim = 0.55f)
    }
}
```

- [ ] **Step 4: Implement BallSim.kt**

```kotlin
package com.hadencain.vox.ui

/** The Bounce ball — pure physics, port of desktop BallSim + the widget's level smoothing.
 *  onLevel is called from the audio capture thread (@Volatile target); step runs on the
 *  UI frame loop. DRIFT: DVD-logo bounce off the glass edges. RIDE_LEVEL: x eases to
 *  center, y chases (floor - level*height) so the ball rides the voice. Other screens
 *  position their visuals from time in the view; the ball is parked. */
class BallSim {
    // Same normalization MouthDriver proved out: ~0.08 RMS on VOICE_RECOGNITION = full send.
    private val fullOpenRms = 0.08f

    var x = 8f; private set
    var y = 9f; private set
    var level = 0f; private set  // smoothed 0..1, read by the view for squash
    private var vx = 2.6f
    private var vy = 1.9f
    @Volatile private var target = 0f

    fun onLevel(rms: Float) {
        target = (rms / fullOpenRms).coerceIn(0f, 1f)
    }

    fun step(dt: Float, screen: ScreenStyle) {
        when (screen) {
            ScreenStyle.RIDE_LEVEL -> {
                level += (target - level) * minOf(1f, dt * 18)  // desktop's dt*18 ease
                x += (11f - x) * minOf(1f, dt * 3)
                val ty = CrtFace.FLOOR - level * (CrtFace.SH - 2.6f)
                y += (ty - y) * minOf(1f, dt * 14)
            }
            ScreenStyle.DRIFT -> {
                x += vx * dt
                y += vy * dt
                if (x < CrtFace.SX + 1 || x > CrtFace.SX2 - 1.4f) {
                    vx = -vx
                    x = maxOf(CrtFace.SX + 1, minOf(CrtFace.SX2 - 1.4f, x))
                }
                if (y < CrtFace.SY + 1 || y > CrtFace.SY2 - 1.4f) {
                    vy = -vy
                    y = maxOf(CrtFace.SY + 1, minOf(CrtFace.SY2 - 1.4f, y))
                }
            }
            else -> {}  // parked; orbit/sweep/flatline are drawn from time, not the ball
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.ui.CrtFaceTest" --tests "com.hadencain.vox.ui.BallSimTest"`
Expected: PASS — all 9 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/ui/CrtFace.kt app/src/main/java/com/hadencain/vox/ui/BallSim.kt app/src/test/java/com/hadencain/vox/ui/CrtFaceTest.kt app/src/test/java/com/hadencain/vox/ui/BallSimTest.kt
git commit -m "CRT bubble: state map + Bounce ball physics (pure logic)"
```

---

### Task 2: CrtView + overlay swap — the ghost retires

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/ui/CrtView.kt`
- Modify: `app/src/main/java/com/hadencain/vox/ui/BubbleOverlay.kt` (the `bubble` field + `setState` body + the audio-level comment)
- Delete: `app/src/main/java/com/hadencain/vox/ui/GhostView.kt`, `app/src/main/java/com/hadencain/vox/ui/GhostFace.kt`, `app/src/main/java/com/hadencain/vox/ui/MouthDriver.kt`, `app/src/test/java/com/hadencain/vox/ui/GhostFaceTest.kt`, `app/src/test/java/com/hadencain/vox/ui/MouthDriverTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produced (`CrtExpressions.forState`, `CrtFace` constants, `BallSim`, `ScreenStyle`, `LampStyle`); `BubbleState` enum.
- Produces: `class CrtView(context) : View { fun setCrtState(state: BubbleState); fun setAudioLevel(rms: Float) }` — only `BubbleOverlay` consumes it.

No JVM test for CrtView itself (Canvas drawing is device-verified, same convention as GhostView); the full existing suite must still pass after the ghost test files are deleted.

- [ ] **Step 1: Implement CrtView.kt**

```kotlin
package com.hadencain.vox.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Procedural Vox CRT — port of desktop status_widget.py's _draw_face. Geometry is
 *  authored on the 24-block grid and scaled to view size. Frame loop runs only while
 *  attached AND visible. No per-frame allocation: one reused Paint, all rects. */
class CrtView(context: Context) : View(context) {
    private var expression = CrtExpressions.forState(BubbleState.IDLE)
    private val ball = BallSim()
    private var lastFrameMs = 0L
    private var running = false

    private val paint = Paint()  // deliberately no AA: crisp pixel blocks
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CrtFace.AMBER
        isFakeBoldText = true
        letterSpacing = 0.15f
    }

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setCrtState(state: BubbleState) {
        expression = CrtExpressions.forState(state)
        invalidate()
    }

    /** Called from the audio capture thread; BallSim's target is @Volatile. */
    fun setAudioLevel(rms: Float) = ball.onLevel(rms)

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility); updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && visibility == VISIBLE
        if (shouldRun && !running) {
            running = true
            removeCallbacks(frameTick)  // avoid double-scheduling on rapid off->on toggles
            postOnAnimation(frameTick)
        }
        else if (!shouldRun) running = false
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1, 100)
        lastFrameMs = now
        val dt = dtMs / 1000f
        // Seconds for lamp/orbit/sweep phases. Wrapped hourly so Float keeps millisecond
        // precision at large uptimes (one visual jump per hour, invisible in practice).
        val t = (now % 3_600_000L) / 1000f
        val b = width / 24f
        val e = expression

        ball.step(dt, e.screen)

        val dimmed = e.dim < 1f
        if (dimmed) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (e.dim * 255).toInt())

        // --- chassis: rounded monitor shell, chin with one button + lamp ---
        px(canvas, b, 4f, 4.5f, CrtFace.BODY_L, 15f, 1f)
        var row = 5.5f
        while (row <= 15.5f) { px(canvas, b, 3f, row, CrtFace.BODY, 17f, 1f); row += 1f }
        px(canvas, b, 3.4f, 16.5f, CrtFace.BODY, 16.2f, 1f)
        px(canvas, b, 4f, 17.3f, CrtFace.BODY_D, 15f, 1f)
        px(canvas, b, CrtFace.SX - .4f, CrtFace.SY - .4f, CrtFace.BODY_D, CrtFace.SW + .8f, CrtFace.SH + .8f)
        px(canvas, b, CrtFace.SX, CrtFace.SY, CrtFace.GLASS, CrtFace.SW, CrtFace.SH)
        px(canvas, b, 5.6f, 15.9f, CrtFace.BODY_D, 1.5f, 1.5f)
        px(canvas, b, 5.95f, 16.25f, CrtFace.BODY_L, .8f, .8f)

        // status lamp
        val lampColor = when (e.lamp) {
            LampStyle.RED_BLINK -> if (t % 0.8f < 0.4f) CrtFace.RED else LAMP_OFF
            LampStyle.RED_SOLID -> CrtFace.RED
            LampStyle.AMBER_BLINK -> if (t % 1.6f < 0.8f) CrtFace.AMBER else LAMP_OFF
            LampStyle.AMBER_DIM -> withAlpha(CrtFace.AMBER, 120)
            LampStyle.OFF -> LAMP_OFF
        }
        px(canvas, b, 17.5f, 16.2f, lampColor, .8f, .8f)

        // RAW badge on the chin (verbatim take in flight)
        if (e.raw) {
            textPaint.textSize = 1.9f * b
            canvas.drawText("RAW", 9f * b, 17.4f * b, textPaint)
        }

        // --- the Bounce screen ---
        when (e.screen) {
            ScreenStyle.RIDE_LEVEL -> {
                val squash = if (ball.y > CrtFace.FLOOR - 0.4f) 0.45f else 0f
                px(canvas, b, ball.x - .7f, ball.y - .5f + squash, CrtFace.BALL,
                   1.4f + squash, 1f - squash * .5f)
                for (i in 1..3) {  // motion trail
                    px(canvas, b, ball.x - .35f, ball.y + i * .9f,
                       withAlpha(CrtFace.BALL, ((0.3f - i * 0.08f) * 255).toInt()), .7f, .5f)
                }
            }
            ScreenStyle.ORBIT -> {
                val a = t * 7f
                px(canvas, b, 11f + sin(a) * 3f - .5f, 10.75f + cos(a) * 2f - .5f, CrtFace.BALL, 1f, 1f)
                px(canvas, b, 11f + sin(a - .8f) * 3f - .35f, 10.75f + cos(a - .8f) * 2f - .35f,
                   withAlpha(CrtFace.BALL, 115), .7f, .7f)
            }
            ScreenStyle.SWEEP -> {
                px(canvas, b, 10f, CrtFace.SY2 - 1.9f, withAlpha(CrtFace.BALL, 179), 2f, .8f)  // deflated
                px(canvas, b, CrtFace.SX + 1, CrtFace.SY2 - 1f, CrtFace.TRACK, CrtFace.SW - 2, .7f)
                val w = (CrtFace.SW - 2) * .28f
                val x = CrtFace.SX + 1 + (CrtFace.SW - 2 - w) * ((t * .8f) % 1f)
                px(canvas, b, x, CrtFace.SY2 - 1f, CrtFace.AMBER, w, .7f)
            }
            ScreenStyle.FLATLINE -> px(canvas, b, CrtFace.SX + 1, 10.4f, CrtFace.RED, CrtFace.SW - 2, .7f)
            ScreenStyle.DRIFT -> px(canvas, b, ball.x - .6f, ball.y - .6f, CrtFace.BALL, 1.2f, 1.2f)
            ScreenStyle.BLANK -> {}
        }

        if (dimmed) canvas.restore()
    }

    private fun px(canvas: Canvas, b: Float, x: Float, y: Float, color: Int, w: Float, h: Float) {
        paint.color = color
        canvas.drawRect(x * b, y * b, (x + w) * b, (y + h) * b, paint)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private companion object {
        val LAMP_OFF = Color.argb(30, 255, 255, 255)
    }
}
```

- [ ] **Step 2: Swap the overlay's character view**

In `BubbleOverlay.kt`, change:

```kotlin
    private val bubble = GhostView(context)
```

to:

```kotlin
    private val bubble = CrtView(context)
```

and in `setState`:

```kotlin
    fun setState(state: BubbleState) {
        currentState = state
        handler.post { bubble.setGhostState(state) }
    }
```

to:

```kotlin
    fun setState(state: BubbleState) {
        currentState = state
        handler.post { bubble.setCrtState(state) }
    }
```

and update the audio-level comment:

```kotlin
    /** Mic level passthrough -- called from the audio capture thread; CrtView handles
     *  cross-thread safety internally. */
    fun setAudioLevel(rms: Float) = bubble.setAudioLevel(rms)
```

- [ ] **Step 3: Delete the ghost**

```bash
git rm app/src/main/java/com/hadencain/vox/ui/GhostView.kt app/src/main/java/com/hadencain/vox/ui/GhostFace.kt app/src/main/java/com/hadencain/vox/ui/MouthDriver.kt app/src/test/java/com/hadencain/vox/ui/GhostFaceTest.kt app/src/test/java/com/hadencain/vox/ui/MouthDriverTest.kt
```

- [ ] **Step 4: Full unit test run + build**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: all remaining tests PASS (the suite shrinks by the deleted ghost tests, grows by Task 1's 9), BUILD SUCCESSFUL. If anything else references the deleted classes, the build will say so — fix the reference, don't resurrect the files.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/hadencain/vox/ui app/src/test/java/com/hadencain/vox/ui
git commit -m "CRT bubble: CrtView replaces GhostView; the ghost retires"
```

---

### Task 3: On-device confirmation (user gate — do not self-close)

**Files:** none (verification only).

- [ ] **Step 1: Install on the S24 Ultra**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, installed.

- [ ] **Step 2: Hand the checklist to the user**

Per project rule, build passes ≠ done. Ask the user to confirm:

1. The bubble is now the CRT — chassis readable at 56dp over light and dark apps; the recessed glass and chin button resolve (not mud).
2. IDLE: ball drifts DVD-style and bounces off the glass edges.
3. RECORDING: red lamp blinks; the ball rides your voice (jumps on speech, settles to the floor in silence) with squash + trail.
4. RECORDING_RAW (tap the caption mid-take): amber RAW appears on the chin.
5. WAKING: dimmed with amber blinking lamp + sweeping amber bar. PROCESSING: ball orbits. ERROR (revoke a11y mid-take): red flatline + solid red lamp. DISABLED: dim standby, blank glass.
6. Drag, tap, long-press, drag-to-close all unregressed; frame loop stops when the bubble is hidden (stop Vox → no residual activity).

- [ ] **Step 3: Fix what the user reports, then re-gate**
