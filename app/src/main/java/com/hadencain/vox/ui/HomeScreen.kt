@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.hadencain.vox.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hadencain.vox.VoxAmber
import com.hadencain.vox.VoxGreen
import com.hadencain.vox.VoxStopped
import com.hadencain.vox.core.History
import com.hadencain.vox.core.HistoryEntry
import com.hadencain.vox.core.Mine
import com.hadencain.vox.core.VoxSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ---- status card --------------------------------------------------------

@Composable
internal fun StatusCard(
    isRunning: Boolean,
    canStart: Boolean,
    settingsDirty: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) VoxGreen else VoxStopped)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isRunning) "Running" else "Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Button(
                onClick = if (isRunning) onStop else onStart,
                enabled = isRunning || canStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (isRunning) "Stop Vox" else "Start Vox")
            }
            if (isRunning) {
                Text(
                    "The floating bubble is active — tap it anywhere to dictate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settingsDirty && isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Settings changed — restart Vox to apply",
                        style = MaterialTheme.typography.bodySmall,
                        color = VoxAmber,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRestart) { Text("Restart") }
                }
            }
        }
    }
}

// ---- setup card --------------------------------------------------------

@Composable
internal fun SetupCard(
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    modelsPresent: Boolean,
    dlFailed: Boolean,
    onRetryModels: () -> Unit,
    overlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    a11yGranted: Boolean,
    onRequestA11y: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Finish setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SetupRow(
                label = "Microphone",
                done = micGranted,
                actionLabel = "Grant",
                onClick = onRequestMic,
            )
            SetupRow(
                label = "Speech models",
                done = modelsPresent,
                failed = dlFailed,
                actionLabel = if (dlFailed) "Retry" else null,
                statusText = when {
                    modelsPresent -> null
                    dlFailed -> "Download failed"
                    else -> "Downloading over Wi-Fi (~740MB)…"
                },
                onClick = if (dlFailed) onRetryModels else null,
            )
            SetupRow(
                label = "Display over other apps",
                done = overlayGranted,
                actionLabel = "Open settings",
                onClick = onRequestOverlay,
            )
            SetupRow(
                label = "Accessibility (recommended)",
                done = a11yGranted,
                actionLabel = "Open settings",
                statusText = if (!a11yGranted)
                    "Lets Vox type your words into other apps. On Samsung: Settings > Accessibility > Installed apps > Vox."
                else null,
                onClick = onRequestA11y,
            )
        }
    }
}

@Composable
private fun SetupRow(
    label: String,
    done: Boolean,
    failed: Boolean = false,
    actionLabel: String?,
    statusText: String? = null,
    onClick: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (done) "✓" else if (failed) "✗" else "○",
            color = if (done) VoxGreen else if (failed) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (statusText != null) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!done && actionLabel != null && onClick != null) {
            FilledTonalButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

// ---- settings cards --------------------------------------------------------

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (sublabel != null) {
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CleanupCard(settings: VoxSettings, onUpdate: (VoxSettings) -> Unit) {
    SettingsCard(title = "Cleanup") {
        SettingSwitchRow(
            label = "Clean up my speech with AI",
            checked = settings.enableCleanup,
            onCheckedChange = { onUpdate(settings.copy(enableCleanup = it)) },
        )
        SettingSwitchRow(
            label = "Adapt to the app I'm typing in",
            sublabel = "Formats differently for chat, email, notes",
            checked = settings.enableContext,
            onCheckedChange = { onUpdate(settings.copy(enableContext = it)) },
        )
    }
}

@Composable
internal fun BehaviorCard(settings: VoxSettings, onUpdate: (VoxSettings) -> Unit) {
    SettingsCard(title = "Behavior") {
        SettingSwitchRow(
            label = "\"Scratch that\" cancels a take",
            checked = settings.enableCommands,
            onCheckedChange = { onUpdate(settings.copy(enableCommands = it)) },
        )
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "%.1fs".format(settings.silenceTimeoutMs / 1000.0),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = settings.silenceTimeoutMs.toFloat(),
                onValueChange = { v ->
                    val snapped = (Math.round(v / 500f) * 500L).coerceIn(2000L, 8000L)
                    onUpdate(settings.copy(silenceTimeoutMs = snapped))
                },
                valueRange = 2000f..8000f,
                steps = 11,
            )
        }
    }
}

@Composable
internal fun DictionaryCard(settings: VoxSettings, onUpdate: (VoxSettings) -> Unit) {
    SettingsCard(title = "Dictionary") {
        // -- bias words --
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Bias words",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Names/jargon Whisper should get right",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (settings.vocab.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                settings.vocab.forEach { word ->
                    InputChip(
                        selected = false,
                        onClick = { onUpdate(settings.copy(vocab = settings.vocab.filterNot { it == word })) },
                        label = { Text(word) },
                        trailingIcon = { Text("✕") },
                    )
                }
            }
        }
        var newWord by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Add a word") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val w = newWord.trim()
                if (w.isNotEmpty() && w !in settings.vocab) onUpdate(settings.copy(vocab = settings.vocab + w))
                newWord = ""
            }) { Text("Add") }
        }

        // -- corrections --
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Corrections",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Replace what's heard with the right spelling",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.corrections.forEach { (heard, fixed) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "$heard → $fixed",
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { onUpdate(settings.copy(corrections = settings.corrections - heard)) }) {
                    Text("✕")
                }
            }
        }
        var newHeard by remember { mutableStateOf("") }
        var newFixed by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newHeard,
                onValueChange = { newHeard = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("heard") },
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = newFixed,
                onValueChange = { newFixed = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("fixed") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val h = newHeard.trim()
                val f = newFixed.trim()
                if (h.isNotEmpty() && f.isNotEmpty()) {
                    onUpdate(settings.copy(corrections = settings.corrections + (h to f)))
                    newHeard = ""
                    newFixed = ""
                }
            }) { Text("Add") }
        }
    }
}

@Composable
internal fun HistoryCard(ctx: Context, resumeTick: Int, historyMax: Int) {
    val historyFile = remember { File(ctx.filesDir, "history.jsonl") }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    fun refresh() {
        entries = History(historyFile, historyMax).readAll().takeLast(20).reversed()
    }
    LaunchedEffect(resumeTick) { refresh() }

    SettingsCard(title = "History") {
        if (entries.isEmpty()) {
            Text(
                "No dictations yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            entries.forEach { e ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        e.cleaned,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                    )
                    Text(
                        "${e.appPackage?.substringAfterLast('.') ?: "unknown"} · ${e.mode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = {
                historyFile.delete()
                refresh()
            }) { Text("Clear history") }
        }
    }
}

@Composable
internal fun SuggestionsCard(ctx: Context, resumeTick: Int, settings: VoxSettings, onUpdate: (VoxSettings) -> Unit) {
    val historyFile = remember { File(ctx.filesDir, "history.jsonl") }
    var suggestions by remember { mutableStateOf(Mine.Suggestions(emptyList(), emptyList())) }
    LaunchedEffect(resumeTick, settings.vocab, settings.corrections) {
        // File I/O + JSON parse + the O(n*m) LCS alignment in Mine.suggest are too heavy for
        // the main thread on every resume/Add tap -- push the work to IO and only touch
        // Compose state with the result.
        suggestions = withContext(Dispatchers.IO) {
            // aiedit rows pair a spoken instruction with unrelated LLM output; aligning them
            // as raw->cleaned produces accidental "corrections" that would then get applied
            // to every future dictation (Dictionary.applyCorrections is global, word-boundary).
            val entries = History(historyFile, settings.historyMax).readAll().filter { it.mode != "aiedit" }
            Mine.suggest(entries, settings.vocab, settings.corrections)
        }
    }

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
