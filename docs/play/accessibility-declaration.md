# Vox — Accessibility Service Declaration for Google Play Console

## Permitted-Use Declaration (ready to paste into Play Console)

**App:** Vox — On-Device Voice Dictation  
**Service:** AccessibilityService  
**Permit:** Yes, used for hands-free text entry and selection reading  

### Use Case

Vox provides fully on-device voice dictation via an AccessibilityService for two purposes:

1. **Text Injection (hands-free typing):** When the user speaks into the floating dictation bubble, Vox uses the AccessibilityService to locate the currently focused editable text field in any foreground app and insert the dictated text as if typed. This is a hands-free input accessibility use — equivalent to how system keyboards and assistive keyboards access the text field.

2. **Selection Reading (AI-edit mode):** When the user long-presses the bubble to activate "AI-edit" mode, Vox reads the user's current selection (highlighted text) from the focused field to enable selection-aware voice commands. The selected text is rewritten locally on-device using the user's voice instruction and then injected back into the original selection range.

### Justification

The AccessibilityService is the only framework-supported way to inject text into arbitrary third-party apps' text fields on Android, independent of the active app or keyboard implementation. Without this service, Vox would be limited to clipboard-fallback injection (app must explicitly support paste) or would require users to manually tap the target field and switch focus — defeating the hands-free entry goal.

### Data Handling

- **No data collected or transmitted.** All processing occurs locally on the device.
- **Selection is never stored.** When reading a selection for AI-edit, the text is held only in memory during the rewrite operation and then immediately injected back.
- **Clipboard read (AI-edit only):** For fallback injection if the accessibility tree does not expose the selection cleanly, Vox reads and immediately restores the user's clipboard. No clipboard contents are retained.
- **Secure fields refused explicitly.** Vox detects password fields and other secure elements flagged by Android's accessibility APIs and refuses injection with a clear toast message ("Can't type into secure fields"). No attempt is made to inject into secure fields.

### Feature Scope in v1

- Core dictation: hold-to-talk floating bubble → Whisper ASR → Gemma LLM cleanup → text injection
- AI-edit mode: long-press bubble → read selection → speak rewrite instruction → inject revised text
- Custom dictionary: post-transcription find/replace (no a11y tree access needed)
- Raw/verbatim mode: skip cleanup for a single take
- "Scratch that" cancel: spoken command to discard the take

All processing is on-device. The one-time model download (first launch, over Wi-Fi) is the only network activity.

---

## Demo Video Shot List (30–60 seconds)

**Goal:** Show hands-free text entry into a real app, plus the secure-field safety guard.

### Scene 1: Onboarding & Permissions (0–5 sec)
- Device locked, no apps open.
- User unlocks, sees Vox main activity for the first time.
- Screen shows "Device check (6GB RAM): PASS ✓" → "Tap to enable AccessibilityService" button (with brief plain-language explanation: "This lets Vox type into your apps").
- User taps button → system deep-link opens Settings > Accessibility > Installed apps > Vox > toggle enabled.
- Toggle animated from OFF to ON.
- User returns to Vox app (back gesture or Settings breadcrumb), screen shows "Service enabled ✓ Ready to dictate".

### Scene 2: Core Dictation Flow (5–20 sec)
- Open Google Keep (or system Notes app) in the foreground.
- Vox floating bubble is visible (docked bottom-right, not obscuring the app).
- User taps the bubble once.
- Bubble animates → "Recording" state (visual indicator, e.g. pulsing red or mic icon).
- Small live-caption sub-bubble appears near the main bubble (or at top of screen), showing streaming ASR partials in real-time as the user speaks.
- User speaks: **"Hello, this is a voice dictation demo for Vox"** (or similar 1–2 sentence natural speech).
- Live captions update phrase-by-phrase, showing ASR streaming.
- User stops speaking (or after 2–3 seconds of silence, auto-stop triggers).
- Bubble returns to "Processing" state (visual spinner).
- After ~2–3 seconds (LLM cleanup running on-device), the caption sub-bubble fades.
- Bubble returns to "Idle" state.
- **Result:** The dictated text appears in the Keep note's focused text field, cleaned up and properly punctuated (e.g. "Hello, this is a voice dictation demo for Vox.").

### Scene 3: AI-Edit Mode (20–35 sec)
- Keep note now contains: "Hello, this is a voice dictation demo for Vox."
- User triple-taps to select the word "demo" (or opens Keep's Edit menu to highlight a word).
- User long-presses the Vox bubble (not a tap; a 1+ second press).
- Bubble animates → "AI-Edit" state (distinct visual, e.g. blue highlight instead of red, with a "thinking" animation).
- Bubble shows "Listening for rewrite command".
- User speaks: **"Make it uppercase"** or **"Change it to 'example'"**.
- Live caption shows the rewrite instruction being streamed.
- After processing, bubble shows "Injecting…".
- **Result:** The selected word changes to "DEMO" (or "example"), with the rest of the sentence intact in the field.
- Bubble returns to Idle.

### Scene 4: Secure-Field Safety Guard (35–45 sec)
- App switches to Google Chrome or system browser.
- User navigates to any login page with a password field (or uses a form with a password input).
- Focus the password field (tap it).
- User taps the Vox bubble.
- Bubble briefly shows "Recording…".
- User speaks: **"test password"** (or any phrase).
- Bubble processes.
- **Toast message appears at bottom:** "Can't type into secure fields" (or similar refusal message).
- **No text is injected** into the password field; it remains empty.
- Bubble returns to Idle, clearly safe.

### Scene 5: Outro (45–60 sec)
- Cut back to Keep note with the successful dictation (Scene 2 result) displayed.
- Overlay text or voiceover: "Vox: 100% on-device, no network, no analytics."
- Final frame shows Vox app icon and tagline.

**Total duration:** ~50–55 seconds of footage (leave 5–10 seconds of breathing room within the 60-second limit).

---

## Key Talking Points for Declaration Narrative

1. **Defensible accessibility use:** Hands-free text entry is a first-class accessibility feature, not a workaround. System keyboards and third-party accessible keyboards use the same APIs.

2. **Minimal scope:** AccessibilityService is used *only* for text injection and selection reading. Vox does not monitor, store, or log any other accessibility events.

3. **Explicit user control:** The service is not enabled by default; the user must explicitly grant it via Settings, and the app explains its purpose in plain language before requesting.

4. **Safety guardrails:** Vox refuses to type into password fields and other secure elements, with a visible refusal message.

5. **No data pipeline:** All processing is on-device. There is no logging, no analytics, no data transmission related to the text being dictated, injected, or read.

6. **Fallback available:** If Vox is unable to use the AccessibilityService (e.g. user revokes it mid-session), text is available via clipboard fallback, ensuring the user is never silently left without recourse.

---

## Notes for Play Console Submission

- **Upload the demo video** to the Play Console listing (Manage Store Listing → Screenshots and video).
- **Mark the service declaration as "Yes"** when Play Console prompts for AccessibilityService use and asks for a permitted-use statement.
- **Paste the declaration above** into the text field (or link to this document if Play Console supports inline markdown).
- **Be ready to clarify** if Play's human review team requests specifics — the hands-free-input framing and the secure-field guardrails are the key points.
- If Play requests additional demo footage, the shot list above can be extended (e.g. compatibility matrix across Gmail, Messages, web forms, etc.).

