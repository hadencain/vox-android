# Vox — Accessibility Service Declaration for Google Play Console

## Permitted-Use Declaration (ready to paste into Play Console)

**App:** Vox — On-Device Voice Dictation  
**Service:** AccessibilityService  
**Permit:** Yes, used for hands-free text entry and selection reading  

### Use Case

Vox exists to provide voice-driven text entry for people who cannot or prefer not to type — motor or dexterity impairment, RSI and other repetitive-strain conditions, or situational hands-free need (hands occupied, device propped up, etc). The AccessibilityService is used for exactly two purposes in support of that:

1. **Text Injection (hands-free typing):** When the user speaks into the floating dictation bubble, Vox uses the AccessibilityService to locate the currently focused editable text field in any foreground app and insert the dictated text as if typed. This is a hands-free input accessibility use — equivalent to how system keyboards and assistive keyboards access the text field.

2. **Selection Reading (AI-edit mode):** When the user long-presses the bubble to activate "AI-edit" mode, Vox reads the user's current selection (highlighted text) from the focused field to enable selection-aware voice commands. The selected text is rewritten locally on-device using the user's voice instruction and then injected back into the original selection range.

**What the service does not do:** Vox does not read screen content beyond the currently focused field, does not monitor accessibility events outside of the two actions above, and no data it reads or writes ever leaves the device. Secure fields (passwords, PINs) are detected and injection into them is refused outright.

**Why accessibility instead of a custom keyboard (IME):** A floating bubble plus accessibility-based insertion works on top of the user's existing keyboard setup, rather than requiring them to switch their system default keyboard to a Vox-provided one — that's the reason for the accessibility approach over building an IME.

### Justification

The AccessibilityService is the only framework-supported way to inject text into arbitrary third-party apps' text fields on Android, independent of the active app or keyboard implementation. Without this service, Vox would be limited to clipboard-fallback injection (app must explicitly support paste) or would require users to manually tap the target field and switch focus — defeating the hands-free entry goal.

### Data Handling

- **Nothing is transmitted off the device.** All processing occurs locally. Vox does keep a local history of dictated text in the app's private storage (on by default) so users can review past dictations — see "Local Dictation History" below. That history never leaves the device, which is why it is not "collected" under Play's Data Safety definitions (collection means data transmitted off-device).
- **Local Dictation History:** Dictated text is saved to a local JSONL log in app-private storage, enabled by default. It is never transmitted anywhere, and can be cleared at any time by clearing the app's data in Android Settings. An in-app toggle to disable this history is planned but not yet available in this version.
- **Selection is never stored.** When reading a selection for AI-edit, the text is held only in memory during the rewrite operation and then immediately injected back.
- **Clipboard use is output-side only, never used to read a selection.** Selection reading for AI-edit is done entirely via the accessibility tree — the clipboard is never used to get text off the screen. The clipboard is touched in exactly two cases when Vox is producing output: (1) if no editable field is focused when a take finishes, Vox copies the transcript to the clipboard instead, with an on-screen toast, so the dictation isn't lost; (2) if direct accessibility-based injection into a field fails, Vox briefly places the text on the clipboard to paste it, then immediately restores whatever was on the clipboard beforehand. Android 12+ shows a system toast whenever an app reads or writes the clipboard, so the user sees both cases when they happen.
- **Secure fields refused explicitly.** Vox detects password fields and other secure elements flagged by Android's accessibility APIs and refuses injection with a clear toast message ("Can't type into secure fields"). No attempt is made to inject into secure fields.

### Feature Scope in v1

- Core dictation: hold-to-talk floating bubble → Whisper ASR → Gemma LLM cleanup → text injection
- AI-edit mode: long-press bubble → read selection → speak rewrite instruction → inject revised text
- Custom dictionary: post-transcription find/replace (no a11y tree access needed)
- Raw/verbatim mode: skip cleanup for a single take
- "Scratch that" cancel: spoken command to discard the take

All processing is on-device. The one-time model download (first launch, Whisper ~190MB + Gemma ~550MB ≈ 740MB total, Wi-Fi-only — enforced, not just recommended) is the only network activity.

---

## Demo Video Shot List (60 seconds)

**Goal:** Show hands-free text entry into a real app, AI-edit on a selection, and the secure-field safety guard.

**Starting state (before recording):** Microphone, overlay, and notification permissions are already granted from a prior first-run — this demo starts with only the AccessibilityService still disabled, so the enable flow itself is on camera.

### Scene 1: Enable AccessibilityService (0–10 sec)
- Vox main activity is open, showing "AccessibilityService: Disabled — tap to enable" with a brief plain-language explanation ("This lets Vox type into your apps").
- User taps the button → system deep-link opens Settings > Accessibility > Installed apps > Vox.
- Toggle animated from OFF to ON, confirmation dialog accepted.
- User returns to Vox (back gesture), screen now shows "Service enabled — Ready to dictate".

### Scene 2: Open Keep and Focus a Note (10–18 sec)
- User opens Google Keep, creates or opens a note.
- Taps into the note body — cursor is now in a focused editable field.
- Vox floating bubble is visible, docked bottom-right, not obscuring the note.

### Scene 3: Core Dictation (18–33 sec)
- User taps the bubble once → "Recording" state (pulsing mic icon).
- Small live-caption sub-bubble shows streaming ASR partials as the user speaks: **"Hello, this is a voice dictation demo for Vox"**.
- User stops speaking; bubble shows "Processing" (on-device LLM cleanup, ~1–2 sec).
- **Result:** cleaned, punctuated text lands in the Keep note's focused field ("Hello, this is a voice dictation demo for Vox."). Bubble returns to Idle.

### Scene 4: Select Text and AI-Edit (33–48 sec)
- User double-taps to select the word "demo" in the note.
- User long-presses the Vox bubble (1+ second) → "AI-Edit" state (distinct blue highlight), shows "Listening for rewrite command".
- User speaks: **"Change it to 'example'"**.
- Bubble shows "Injecting…".
- **Result:** the selected word changes to "example", rest of the sentence intact. Bubble returns to Idle.

### Scene 5: Secure-Field Refusal (48–55 sec)
- User switches to a login screen with a password field and taps into it.
- User taps the Vox bubble → briefly shows "Recording…", user speaks a short phrase.
- Bubble processes, then a **toast appears: "Can't type into secure fields."**
- No text is injected; the password field remains empty. Bubble returns to Idle.

### Scene 6: End Card (55–60 sec)
- Cut to Vox app icon and tagline over a static frame.
- Overlay text: "Vox — 100% on-device voice dictation."

**Total: 60 seconds** (10 + 8 + 15 + 15 + 7 + 5).

---

## Key Talking Points for Declaration Narrative

1. **Defensible accessibility use:** Hands-free text entry is a first-class accessibility feature, not a workaround. System keyboards and third-party accessible keyboards use the same APIs.

2. **Minimal scope:** AccessibilityService is used *only* for text injection and selection reading. Vox does not monitor, store, or log any other accessibility events.

3. **Explicit user control:** The service is not enabled by default; the user must explicitly grant it via Settings, and the app explains its purpose in plain language before requesting.

4. **Safety guardrails:** Vox refuses to type into password fields and other secure elements, with a visible refusal message.

5. **Nothing leaves the device:** All processing is on-device, and there is no logging, analytics, or transmission of dictated, injected, or read text. Vox does keep a local, on-device-only history of dictations for user review (on by default, clearable via app data) — see the Data Handling section above.

6. **Fallback available:** If accessibility-based injection fails or isn't usable in a given field, the text falls back to the clipboard (briefly set, then the user's prior clipboard is restored) or is copied out for manual paste, ensuring the user is never silently left without recourse.

---

## Notes for Play Console Submission

- **Upload the demo video** to the Play Console listing (Manage Store Listing → Screenshots and video).
- **Mark the service declaration as "Yes"** when Play Console prompts for AccessibilityService use and asks for a permitted-use statement.
- **Paste the declaration above** into the text field (or link to this document if Play Console supports inline markdown).
- **Be ready to clarify** if Play's human review team requests specifics — the hands-free-input framing and the secure-field guardrails are the key points.
- If Play requests additional demo footage, the shot list above can be extended (e.g. compatibility matrix across Gmail, Messages, web forms, etc.).

