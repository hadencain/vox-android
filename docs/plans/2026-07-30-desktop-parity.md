# Desktop Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the real gaps found in a full parity audit against desktop Vox (`src/vox/dictation/`): a privacy regression in raw-mode history, missing take-state feedback, and a missing "mine history → suggest dictionary entries" feature.

**Architecture:** Desktop Vox is the reference implementation for pipeline behavior (see project `CLAUDE.md`: "Pipeline logic/prompts port from `src/vox/dictation/`"). The audit (2026-07-30) found most desktop features already ported with full parity (vocab, history, commands, AI-edit, context-aware cleanup); the injection-race fix desktop needed is structurally unnecessary here because `Pipeline`'s single-threaded `stateDispatcher` already serializes all state access. Three concrete gaps remain, each a self-contained task below. A fourth candidate gap (mic-source selection) was checked against desktop's `config.py`/`c4c05d8` device-picker logic and found **not applicable** — `AudioCapture.kt` already uses `MediaRecorder.AudioSource.VOICE_RECOGNITION`, which Android's audio policy routes correctly (including Bluetooth) without app-level device selection; no task needed.

**Tech Stack:** Kotlin, `kotlinx.serialization` (settings/history JSON), JUnit4 (JVM unit tests, no Robolectric — `android.*` framework classes are not available in this project's unit tests, so tasks that touch them are verified by build + on-device check instead of a JVM test).

## Global Constraints

- Spec of record for the pipeline: `docs/specs/2026-07-09-vox-android-design.md`. Reference implementation for behavior: `src/vox/dictation/` (sibling project, absolute path `C:/Users/haden/Documents/Ship/src/vox/dictation/`).
- Privacy invariant (the reason Task 1 exists): raw/verbatim-mode dictation exists specifically for passwords and exact quotes. That text must never be written to plaintext disk. Desktop learned this the hard way (`3ddbd53`); port the same guarantee.
- Package convention already established in this codebase: `com.hadencain.vox.core` holds pure-JVM logic with **zero** `android.*` imports (verified: `Commands.kt`, `ContextMap.kt`, `Dictionary.kt`, `History.kt`, `VoxSettings.kt` all import nothing from `android.*`) so it stays unit-testable without Robolectric. Anything that touches `Context`/`Vibrator`/etc. goes in the root `com.hadencain.vox` package alongside `Pipeline.kt`/`VoxService.kt`, not in `core/`.
- minSdk 33, arm64-v8a only. Build: `./gradlew assembleDebug`. Unit tests: `./gradlew testDebugUnitTest`. Build passing ≠ done — the final task is an on-device user gate, per project `CLAUDE.md`.
- NEVER use worktree isolation — work in place.
- Never add `Co-Authored-By` trailers to commit messages.

---

### Task 1: Redact raw-mode takes in History (privacy fix)

**Files:**
- Modify: `app/src/main/java/com/hadencain/vox/core/History.kt`
- Test: `app/src/test/java/com/hadencain/vox/core/HistoryTest.kt` (extend, do not rewrite)

**Interfaces:**
- Consumes: `HistoryEntry(timestampMs: Long, raw: String, cleaned: String, appPackage: String?, mode: String)` (unchanged, already exists).
- Produces: `History.REDACTED: String` (new public constant, for tests and any future UI that wants to recognize a redacted row).

- [ ] **Step 1: Write the failing tests**

Append to `HistoryTest.kt` (it already has a `tmp` `TemporaryFolder` rule and an `entry(n)` helper — reuse both):

```kotlin
@Test fun `redacts raw-mode entries before writing`() {
    val h = History(File(tmp.root, "h.jsonl"), maxEntries = 10)
    h.append(HistoryEntry(1L, "my password is hunter2", "my password is hunter2", null, "raw"))
    val stored = h.readAll().single()
    assertEquals(History.REDACTED, stored.raw)
    assertEquals(History.REDACTED, stored.cleaned)
}
@Test fun `does not redact dictate or aiedit entries`() {
    val h = History(File(tmp.root, "h.jsonl"), maxEntries = 10)
    h.append(entry(1))
    h.append(HistoryEntry(2L, "raw text", "cleaned text", null, "aiedit"))
    val all = h.readAll()
    assertEquals("clean 1", all[0].cleaned)
    assertEquals("cleaned text", all[1].cleaned)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.HistoryTest"`
Expected: FAIL — `redacts raw-mode entries before writing` fails (stored.raw is the plaintext, not `History.REDACTED`); the other new test also fails to compile until `REDACTED` exists.

- [ ] **Step 3: Redact in `append`**

In `History.kt`, replace the current class body:

```kotlin
/** Local JSONL dictation log, trimmed to maxEntries on append (port of desktop history.py). */
class History(private val file: File, private val maxEntries: Int) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(entry: HistoryEntry) {
        val all = readAll() + entry
        val kept = all.takeLast(maxEntries)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("\n") { json.encodeToString(it) } + "\n")
    }
```

with:

```kotlin
/** Local JSONL dictation log, trimmed to maxEntries on append (port of desktop history.py). */
class History(private val file: File, private val maxEntries: Int) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun append(entry: HistoryEntry) {
        // Raw/verbatim takes exist for passwords and exact quotes (see project CLAUDE.md) --
        // persisting that text to plaintext history would be the one place this local tool
        // leaks secrets. Port of desktop history.py's redact=True path.
        val toWrite = if (entry.mode == "raw") entry.copy(raw = REDACTED, cleaned = REDACTED) else entry
        val all = readAll() + toWrite
        val kept = all.takeLast(maxEntries)
        file.parentFile?.mkdirs()
        file.writeText(kept.joinToString("\n") { json.encodeToString(it) } + "\n")
    }
```

And add a companion object at the end of the class (after `readAll`, before the closing brace):

```kotlin
    companion object {
        const val REDACTED = "[verbatim — redacted]"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.HistoryTest"`
Expected: PASS — all tests including the two new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/core/History.kt app/src/test/java/com/hadencain/vox/core/HistoryTest.kt
git commit -m "Redact raw-mode dictation text before writing to history"
```

---

### Task 2: Haptic feedback on take state transitions

**Files:**
- Modify: `app/src/main/java/com/hadencain/vox/core/VoxSettings.kt`
- Test: `app/src/test/java/com/hadencain/vox/core/VoxSettingsTest.kt` (extend, do not rewrite)
- Create: `app/src/main/java/com/hadencain/vox/Haptics.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/hadencain/vox/Pipeline.kt` (4 call sites — see Step 5)
- Modify: `app/src/main/java/com/hadencain/vox/ui/HomeScreen.kt` (`BehaviorCard`)

**Interfaces:**
- Consumes: none new.
- Produces: `VoxSettings.enableHaptics: Boolean` (default `true`); `object Haptics { fun start(ctx: Context); fun stop(ctx: Context); fun done(ctx: Context); fun error(ctx: Context) }`.

- [ ] **Step 1: Write the failing test for the settings field**

In `VoxSettingsTest.kt`, extend the existing `defaults load when file missing` test:

```kotlin
@Test fun `defaults load when file missing`() {
    val s = VoxSettings.load(File(tmp.root, "nope.json"))
    assertTrue(s.enableCleanup)
    assertTrue(s.enableHaptics)
    assertEquals("en", s.language)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.VoxSettingsTest"`
Expected: FAIL to compile — `enableHaptics` unresolved.

- [ ] **Step 3: Add the settings field**

In `VoxSettings.kt`, add a field between `enableCommands` and `silenceTimeoutMs`:

```kotlin
    // voice commands
    val enableCommands: Boolean = true,
    // feedback
    val enableHaptics: Boolean = true,
    // capture
    val silenceTimeoutMs: Long = 4000,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.VoxSettingsTest"`
Expected: PASS.

- [ ] **Step 5: Add `Haptics.kt` and the manifest permission**

Create `app/src/main/java/com/hadencain/vox/Haptics.kt`:

```kotlin
package com.hadencain.vox

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** Haptic cues on take state transitions (port of desktop sounds.py). The bubble is always
 *  visible while Vox runs, unlike desktop's hidden pythonw window, so a short tick is a
 *  confirmation here rather than the primary signal the audio cues were on desktop. */
object Haptics {
    private fun vibrator(ctx: Context) =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    private fun oneShot(ctx: Context, ms: Long) {
        vibrator(ctx).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun start(ctx: Context) = oneShot(ctx, 20)
    fun stop(ctx: Context) = oneShot(ctx, 20)
    fun done(ctx: Context) = oneShot(ctx, 15)
    fun error(ctx: Context) = vibrator(ctx).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
}
```

In `app/src/main/AndroidManifest.xml`, add the permission (`VIBRATE` is a normal-protection permission — declaring it is enough, no runtime request):

```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.VIBRATE" />
```

- [ ] **Step 6: Wire the four call sites into `Pipeline.kt`**

In `handleStartTake`, after the RECORDING state transition (the block that sets `capture = newCapture`):

```kotlin
                capture = newCapture
                state = PipelineState.RECORDING
                service.bubble.setState(BubbleState.RECORDING)
                if (settings.enableHaptics) Haptics.start(service)
                service.bubble.setCaption(takeStartCaption())
```

At the top of `handleStopTake`:

```kotlin
    private fun handleStopTake() {
        if (state != PipelineState.RECORDING) return
        state = PipelineState.PROCESSING
        service.bubble.setState(BubbleState.PROCESSING)
        if (settings.enableHaptics) Haptics.stop(service)
        scope.launch(stateDispatcher) {
```

In the AI-edit success path (right before its `finishIdle()`):

```kotlin
                    if (settings.saveHistory) history.append(HistoryEntry(
                        System.currentTimeMillis(), raw, editResult, targetPackage, "aiedit"))
                    if (settings.enableHaptics) Haptics.done(service)
                    finishIdle(); return@launch
                }
```

In the dictate success path (right before its `finishIdle()`):

```kotlin
                if (settings.saveHistory) history.append(HistoryEntry(
                    System.currentTimeMillis(), raw, final, targetPackage,
                    if (rawMode) "raw" else "dictate"))
                if (settings.enableHaptics) Haptics.done(service)
                finishIdle()
```

In `fail`, before `scheduleUnload()`:

```kotlin
    private fun fail(msg: String) {
        Log.e("Vox", "pipeline: $msg")
        state = PipelineState.IDLE
        aiEditMode = false
        aiEditSelection = null
        service.bubble.setState(BubbleState.ERROR)
        service.bubble.setCaption("⚠ $msg")
        if (settings.enableHaptics) Haptics.error(service)
        toast("Vox: $msg")
        scheduleUnload()
```

Note: `finishIdle()`'s two other callers (the <0.5s-sample short-circuit and the cancel/empty-transcript branch) intentionally get **no** haptic — this matches desktop, where `_process`'s early returns for "no speech" and "cancel" don't beep either.

- [ ] **Step 7: Add the settings toggle to `BehaviorCard`**

In `HomeScreen.kt`, in `BehaviorCard`, insert a new switch row between the "Keep a local history" row and the silence-timeout `Column`:

```kotlin
        SettingSwitchRow(
            label = "Keep a local history of dictations",
            checked = settings.saveHistory,
            onCheckedChange = { onUpdate(settings.copy(saveHistory = it)) },
        )
        SettingSwitchRow(
            label = "Vibrate on start / stop / done / error",
            checked = settings.enableHaptics,
            onCheckedChange = { onUpdate(settings.copy(enableHaptics = it)) },
        )
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Auto-stop after silence",
```

- [ ] **Step 8: Full build + full unit test run**

Run: `./gradlew testDebugUnitTest && ./gradlew assembleDebug`
Expected: all tests PASS, BUILD SUCCESSFUL. (`Haptics.kt` itself has no JVM test — it's a thin `Vibrator` wrapper with no logic branch worth mocking, and this project doesn't use Robolectric; it's covered by the on-device gate in Task 5.)

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/core/VoxSettings.kt app/src/test/java/com/hadencain/vox/core/VoxSettingsTest.kt app/src/main/java/com/hadencain/vox/Haptics.kt app/src/main/AndroidManifest.xml app/src/main/java/com/hadencain/vox/Pipeline.kt app/src/main/java/com/hadencain/vox/ui/HomeScreen.kt
git commit -m "Add haptic feedback on take start/stop/done/error"
```

---

### Task 3: Mine — pure-logic port of desktop mine.py

**Files:**
- Create: `app/src/main/java/com/hadencain/vox/core/Mine.kt`
- Test: `app/src/test/java/com/hadencain/vox/core/MineTest.kt`

**Interfaces:**
- Consumes: `HistoryEntry(timestampMs, raw, cleaned, appPackage, mode)` (from Task 1's `History.kt`, unchanged).
- Produces: `data class Mine.Correction(heard: String, canonical: String, count: Int)`; `data class Mine.BiasTerm(term: String, count: Int)`; `data class Mine.Suggestions(corrections: List<Mine.Correction>, biasTerms: List<Mine.BiasTerm>)`; `object Mine { fun mineCorrections(entries: List<HistoryEntry>, minCount: Int = 2): List<Correction>; fun mineBiasTerms(entries: List<HistoryEntry>, minCount: Int = 2): List<BiasTerm>; fun suggest(entries: List<HistoryEntry>, existingVocab: List<String>, existingCorrections: Map<String, String>, minCount: Int = 2): Suggestions }`. Task 4 consumes all of these.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/hadencain/vox/core/MineTest.kt`:

```kotlin
package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Test

class MineTest {
    private fun e(raw: String, cleaned: String) = HistoryEntry(0L, raw, cleaned, null, "dictate")

    @Test fun `finds a repeated single-word mishearing`() {
        val entries = listOf(
            e("I use juce for embedded audio", "I use deuce for embedded audio"),
            e("open the juce project", "open the deuce project"),
        )
        val result = Mine.mineCorrections(entries)
        assertEquals(listOf(Mine.Correction("juce", "deuce", 2)), result)
    }

    @Test fun `ignores casing-only changes`() {
        val entries = listOf(e("hello world", "Hello world"), e("hello there", "Hello there"))
        assertTrue(Mine.mineCorrections(entries).isEmpty())
    }

    @Test fun `respects minCount threshold`() {
        val entries = listOf(e("I use juce here", "I use deuce here"))
        assertTrue(Mine.mineCorrections(entries, minCount = 2).isEmpty())
        assertEquals(1, Mine.mineCorrections(entries, minCount = 1).size)
    }

    @Test fun `alignment survives filler-word removal`() {
        val entries = listOf(
            e("um I use juce", "I use deuce"),
            e("uh open juce now", "open deuce now"),
        )
        assertEquals(listOf(Mine.Correction("juce", "deuce", 2)), Mine.mineCorrections(entries))
    }

    @Test fun `finds recurring capitalized proper nouns in cleaned text`() {
        val entries = listOf(
            e("meet me at the place tomorrow", "Meet me at Overlook tomorrow"),
            e("is the place closed", "Is Overlook closed"),
        )
        val result = Mine.mineBiasTerms(entries)
        assertEquals(listOf(Mine.BiasTerm("Overlook", 2)), result)
    }

    @Test fun `bias terms filter stopwords and short words`() {
        val entries = listOf(e("hi", "Hi there"), e("hi", "Hi there"))
        assertTrue(Mine.mineBiasTerms(entries).none { it.term == "Hi" })
    }

    @Test fun `suggest filters out terms already known`() {
        val entries = listOf(
            e("I use juce here", "I use deuce here"),
            e("open juce now", "open deuce now"),
            e("meet at Overlook", "meet at Overlook"),
            e("Overlook is closed", "Overlook is closed"),
        )
        val known = Mine.suggest(entries, existingVocab = listOf("overlook"), existingCorrections = mapOf("juce" to "deuce"))
        assertTrue(known.corrections.isEmpty())
        assertTrue(known.biasTerms.isEmpty())
        val fresh = Mine.suggest(entries, existingVocab = emptyList(), existingCorrections = emptyMap())
        assertEquals(1, fresh.corrections.size)
        assertEquals(1, fresh.biasTerms.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.MineTest"`
Expected: FAIL to compile — `Mine` does not exist yet.

- [ ] **Step 3: Implement `Mine.kt`**

Create `app/src/main/java/com/hadencain/vox/core/Mine.kt`:

```kotlin
package com.hadencain.vox.core

/** Mines dictation history for dictionary suggestions (port of desktop mine.py).
 *  Two signals, both human-in-the-loop (the Suggestions UI shows them, the user decides
 *  whether to add each one):
 *  - corrections: repeated single-word raw->cleaned substitutions (a consistent mishearing).
 *  - bias terms: recurring capitalized/hyphenated words in cleaned output -- proper-noun
 *    candidates worth adding as Whisper bias words so they're spelled right up front.
 *  Noisy by nature (cleanup also strips filler / fixes casing), so nothing is auto-applied. */
object Mine {
    private val WORD = Regex("[A-Za-z][A-Za-z'\\-]+")

    private val STOP = setOf(
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "for", "with",
        "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those",
        "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your",
        "um", "uh", "like", "know", "mean", "well", "okay", "just", "really", "actually",
        "if", "then", "than", "as", "at", "by", "from", "not", "no", "yes", "do", "does", "did",
        "have", "has", "had", "will", "would", "can", "could", "should", "there", "here", "what",
        "when", "where", "who", "how", "why", "which", "about", "into", "out", "up", "down",
    )

    private fun tokens(s: String): List<String> = WORD.findAll(s).map { it.value }.toList()

    data class Correction(val heard: String, val canonical: String, val count: Int)
    data class BiasTerm(val term: String, val count: Int)
    data class Suggestions(val corrections: List<Correction>, val biasTerms: List<BiasTerm>)

    /** Repeated single-word substitutions raw->cleaned, sorted by count desc. Skips
     *  casing-only changes (those surface as bias terms instead). */
    fun mineCorrections(entries: List<HistoryEntry>, minCount: Int = 2): List<Correction> {
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        for (e in entries) {
            for ((heard, canon) in alignedReplacements(tokens(e.raw), tokens(e.cleaned))) {
                if (heard.equals(canon, ignoreCase = true)) continue  // casing-only
                if (heard in STOP || canon.lowercase() in STOP) continue
                if (heard.length < 3 || canon.length < 3) continue
                val key = heard to canon
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.entries
            .map { (k, n) -> Correction(k.first, k.second, n) }
            .filter { it.count >= minCount }
            .sortedByDescending { it.count }
    }

    /** Recurring capitalized / ALLCAPS / hyphenated tokens in cleaned output. */
    fun mineBiasTerms(entries: List<HistoryEntry>, minCount: Int = 2): List<BiasTerm> {
        val counts = LinkedHashMap<String, Int>()
        for (e in entries) {
            for (t in tokens(e.cleaned)) {
                if (t.length < 3 || t.lowercase() in STOP) continue
                val isProper = t == t.uppercase() ||
                    (t[0].isUpperCase() && t.substring(1) == t.substring(1).lowercase()) ||
                    t.contains('-')
                if (isProper) counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts.entries
            .map { (t, n) -> BiasTerm(t, n) }
            .filter { it.count >= minCount }
            .sortedByDescending { it.count }
    }

    /** Both lists, with anything already in the user's dictionary filtered out. */
    fun suggest(
        entries: List<HistoryEntry>,
        existingVocab: List<String>,
        existingCorrections: Map<String, String>,
        minCount: Int = 2,
    ): Suggestions {
        val haveVocab = existingVocab.map { it.lowercase() }.toSet()
        val haveCorr = existingCorrections.keys.map { it.lowercase() }.toSet()
        return Suggestions(
            corrections = mineCorrections(entries, minCount).filter { it.heard.lowercase() !in haveCorr },
            biasTerms = mineBiasTerms(entries, minCount).filter { it.term.lowercase() !in haveVocab },
        )
    }

    /** Word-level alignment between raw and cleaned tokens (LCS-based diff), yielding only
     *  single-word-for-single-word replacement gaps -- a from-scratch equivalent of Python's
     *  difflib.SequenceMatcher opcode filtering (op == "replace", 1-for-1 spans only), since
     *  the JVM has no difflib. Matching is case-insensitive (mirrors desktop lowercasing both
     *  sides before diffing); the returned canonical form keeps cleaned's original casing. */
    private fun alignedReplacements(a: List<String>, b: List<String>): List<Pair<String, String>> {
        val al = a.map { it.lowercase() }
        val bl = b.map { it.lowercase() }
        val n = al.size; val m = bl.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (al[i] == bl[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = mutableListOf<Pair<String, String>>()
        var i = 0; var j = 0
        var gapStartI = 0; var gapStartJ = 0
        fun flushGap(endI: Int, endJ: Int) {
            if (endI - gapStartI == 1 && endJ - gapStartJ == 1) out.add(al[gapStartI] to b[gapStartJ])
        }
        while (i < n && j < m) {
            if (al[i] == bl[j]) { flushGap(i, j); i++; j++; gapStartI = i; gapStartJ = j }
            else if (dp[i + 1][j] >= dp[i][j + 1]) i++ else j++
        }
        flushGap(n, m)
        return out
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.hadencain.vox.core.MineTest"`
Expected: PASS — all 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/core/Mine.kt app/src/test/java/com/hadencain/vox/core/MineTest.kt
git commit -m "Port desktop mine.py: mine history for dictionary suggestions"
```

---

### Task 4: Suggestions card — surface Mine.kt output in settings

**Files:**
- Modify: `app/src/main/java/com/hadencain/vox/ui/HomeScreen.kt` (new `SuggestionsCard` composable)
- Modify: `app/src/main/java/com/hadencain/vox/MainActivity.kt` (wire it into the settings column)

**Interfaces:**
- Consumes: `Mine.suggest(...)` and its result types (Task 3); `History` (Task 1); `VoxSettings` (existing).
- Produces: `SuggestionsCard(ctx: Context, resumeTick: Int, settings: VoxSettings, onUpdate: (VoxSettings) -> Unit)` composable — no other file depends on this.

No JVM test for this step: this project's other Compose cards (`StatusCard`, `SetupCard`, `DictionaryCard`, `HistoryCard`) have no unit tests either — Compose UI here is verified on-device (Task 5), not via JVM tests.

- [ ] **Step 1: Add `SuggestionsCard` to `HomeScreen.kt`**

Add the import alongside the existing `com.hadencain.vox.core.*` imports near the top of the file:

```kotlin
import com.hadencain.vox.core.Mine
```

Add the composable after `HistoryCard` (end of file):

```kotlin
@Composable
internal fun SuggestionsCard(ctx: Context, resumeTick: Int, settings: VoxSettings, onUpdate: (VoxSettings) -> Unit) {
    val historyFile = remember { File(ctx.filesDir, "history.jsonl") }
    var suggestions by remember { mutableStateOf(Mine.Suggestions(emptyList(), emptyList())) }
    fun refresh() {
        val entries = History(historyFile, settings.historyMax).readAll()
        suggestions = Mine.suggest(entries, settings.vocab, settings.corrections)
    }
    LaunchedEffect(resumeTick, settings.vocab, settings.corrections) { refresh() }

    if (suggestions.corrections.isEmpty() && suggestions.biasTerms.isEmpty()) return

    SettingsCard(title = "Suggestions") {
        Text(
            "Mined from your dictation history — add the ones that look right",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        suggestions.corrections.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${c.heard} → ${c.canonical} (${c.count}×)",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { onUpdate(settings.copy(corrections = settings.corrections + (c.heard to c.canonical))) }) {
                    Text("Add")
                }
            }
        }
        suggestions.biasTerms.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${t.term} (${t.count}×)",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { onUpdate(settings.copy(vocab = settings.vocab + t.term)) }) {
                    Text("Add")
                }
            }
        }
    }
}
```

(`LaunchedEffect` keyed on `settings.vocab`/`settings.corrections` too, not just `resumeTick` — otherwise adding a suggestion wouldn't remove it from the list until the next resume.)

- [ ] **Step 2: Wire it into `MainActivity.kt`**

Add the import next to the other `com.hadencain.vox.ui.*` card imports:

```kotlin
import com.hadencain.vox.ui.SuggestionsCard
```

In the settings `Column`, insert it between `DictionaryCard` and `HistoryCard`:

```kotlin
        CleanupCard(settings) { updateSettings(it) }
        BehaviorCard(settings) { updateSettings(it) }
        DictionaryCard(settings) { updateSettings(it) }
        if (showHistory) SuggestionsCard(ctx, resumeTick, settings) { updateSettings(it) }
        if (showHistory) HistoryCard(ctx, resumeTick, settings.historyMax)
```

- [ ] **Step 3: Full build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hadencain/vox/ui/HomeScreen.kt app/src/main/java/com/hadencain/vox/MainActivity.kt
git commit -m "Surface mined dictionary suggestions in the settings screen"
```

---

### Task 5: On-device confirmation (user gate — do not self-close)

**Files:** none (verification only).

- [ ] **Step 1: Install on the S24 Ultra**

Run: `./gradlew installDebug`
Expected: BUILD SUCCESSFUL, app installed via adb.

- [ ] **Step 2: Hand the checklist to the user**

Per project rule, build passing ≠ done. Report "build passes, needs your test" and ask the user to confirm each of:

1. **Privacy (Task 1):** do a raw/verbatim take (tap the caption during a take to toggle raw mode), then open the History card — the entry shows `[verbatim — redacted]`, not the actual words. A normal (non-raw) take still shows real text.
2. **Haptics (Task 2):** feel a short tick when a take starts, another when it stops (tap to end), a lighter tick when text is successfully injected, and a distinct double-buzz on a forced error (e.g. revoke Accessibility mid-take). Toggle "Vibrate on start / stop / done / error" off in Behavior settings and confirm the ticks stop.
3. **Suggestions (Tasks 3–4):** dictate the same made-up/uncommon word a few times across separate takes so cleanup consistently mishears or capitalizes it, then check the settings screen for a new "Suggestions" card; tapping "Add" moves it into Dictionary and the suggestion disappears.
4. No regressions: normal dictation, AI-edit (long-press), and "scratch that" cancel still all work as before.

- [ ] **Step 3: Fix what the user reports, then re-gate**
