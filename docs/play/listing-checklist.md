# Vox — Play Store Listing Checklist

**App:** Vox — On-Device Voice Dictation  
**Package:** com.hadencain.vox  
**Min SDK:** 33  
**Target SDK:** 35  
**Architecture:** arm64-v8a only  

---

## Privacy Policy (ready to copy to Play Console and hosted privacy policy)

### Full Privacy Policy Text

**Last updated:** [INSERT DATE OF RELEASE]

#### Overview

Vox is a fully on-device voice dictation app. Your privacy is paramount. **We do not transmit or share your dictated text, audio, selections, or personal data with anyone — everything is processed locally on your device.** Vox does keep a local history of your dictations in the app's private storage so you can review past takes (see "Local Dictation History" below); that history never leaves your phone.

#### What Vox Does

1. **Voice Dictation:** When you tap the Vox floating bubble and speak, your microphone is accessed locally. The audio is processed by:
   - **Whisper.cpp** (speech-to-text, running on your device's GPU via JNI)
   - **MediaPipe Gemma LLM** (text cleanup, running on your device's CPU via JNI)
   - No audio leaves your device. No audio is stored.

2. **Text Injection:** Vox uses Android's AccessibilityService framework to locate your currently focused text field and inject the dictated (and cleaned) text. This is equivalent to how system keyboards type.

3. **Selection Reading (AI-Edit Mode):** When you long-press the bubble to rewrite selected text, Vox reads the current selection from the accessibility tree, processes the rewrite instruction locally, and injects the revised text back. Selections are never stored. No clipboard is involved in reading the selection.

4. **Clipboard (output only):** Vox touches the clipboard in exactly two cases, both when producing output: (a) if no editable field is focused when a take finishes, the transcript is copied to the clipboard instead, with an on-screen toast, so it isn't lost; (b) if direct injection into a field fails, Vox briefly places the text on the clipboard to paste it, then immediately restores your prior clipboard contents. Android 12+ shows a system toast on every clipboard read/write, so you'll see both cases when they happen.

5. **Local Dictation History:** Vox keeps a local history of your dictated text in the app's private storage (a JSONL log), on by default, so you can review past dictations. This history is stored only on your device, is never transmitted anywhere, and can be cleared at any time by clearing the app's data in Android Settings. An in-app toggle to turn this off is planned but not yet available in this version.

6. **Model Download (First Launch Only):** On your first use, Vox downloads the Whisper (~190MB) and Gemma (~550MB) model files — about 740MB combined — over a Wi-Fi-only connection (enforced by the OS download manager, not just recommended). This is a one-time, resumable download. **After download, zero network calls occur.** The app is entirely offline.

#### What Vox Does NOT Transmit or Share

*(Vox does keep a local, on-device-only history of your dictated text — see "Local Dictation History" above. Nothing below is ever transmitted off your device or shared with anyone, including us.)*

- ❌ Your dictated text — never leaves the device (stored locally only, see above)
- ❌ Your voice/audio
- ❌ Your selected text or edits
- ❌ Your app usage or behavior
- ❌ Crash reports or telemetry
- ❌ Analytics or event tracking
- ❌ Advertising data
- ❌ Your location, contacts, calendar, or any other personal data

#### Permissions

- **RECORD_AUDIO** — Captures microphone audio for on-device dictation.
- **SYSTEM_ALERT_WINDOW** — Displays the floating dictation bubble over other apps.
- **FOREGROUND_SERVICE** — Keeps the dictation service running while you use other apps.
- **FOREGROUND_SERVICE_MICROPHONE** — Required by Android to run a foreground service that uses the microphone.
- **POST_NOTIFICATIONS** — Shows the persistent notification required while the foreground service is active.
- **INTERNET** — Used exclusively for the one-time model download; you can verify no other network traffic occurs after setup.
- **Accessibility Service (bound via `BIND_ACCESSIBILITY_SERVICE`)** — Not a manifest permission you grant like the others; enabled separately in Settings. Lets Vox find your currently focused text field to insert dictated text, and read an explicit selection for AI-edit mode. Used for nothing else.

#### Security

- All processing is performed locally on your device.
- The Whisper and Gemma models are open-source and can be inspected.
- No credentials, tokens, or authentication are required (no account, no sign-up).
- Secure fields (password inputs, PINs) are explicitly refused with a visible message.

#### Changes to This Policy

If we update this policy, we will notify you by updating the "Last updated" date above and by providing notice in the app before the change takes effect. Your continued use of Vox after such notice constitutes your acceptance.

#### Contact

For privacy questions or concerns, contact: [INSERT CONTACT EMAIL]

---

## Data Safety Form — Play Console Answers

**Note on scope:** Play's Data Safety definitions treat "collection" as data transmitted off the user's device — purely on-device storage that the app never sends anywhere does not count as "collected" or "shared." Vox keeps a local, on-device-only history of dictated text (see the Privacy Policy's "Local Dictation History" section above); it is never transmitted, so the answers below are truthful "No"s in Play's terms, but they are not a claim that Vox stores nothing at all on the device.

### 1. Does this app collect or share user data?

**Answer:** No (off-device transmission/sharing). Note: Vox keeps a local, on-device-only dictation history that is never transmitted — see scope note above.

### 2. If yes, which categories of user data are collected or shared?

**Answer:** N/A (not applicable — no data is transmitted off-device or shared)

### 3. Does this app use encrypted transport?

**Answer:** No network transmission occurs after the initial model download, which uses standard HTTPS.

### 4. Is data collected encrypted in transit?

**Answer:** Not applicable — the app does not transmit user data off the device.

### 5. Is data stored encrypted at rest?

**Answer:** Not applicable in the Play Data Safety sense — no collected (i.e. transmitted) data exists to store server-side. (The on-device dictation history uses standard Android app-private storage, sandboxed to Vox by the OS.)

### 6. Do users have the ability to request deletion of data collected about them?

**Answer:** Not applicable — no data is collected/transmitted off-device. The on-device dictation history can be cleared at any time by clearing the app's data in Android Settings.

### 7. Is this a kids app?

**Answer:** No (target audience is adults; consider age rating in ESRB section below)

### 8. Does your app target children under 13?

**Answer:** No

### 9. Does your app collect or use personally identifiable information from children?

**Answer:** No

---

## Store Listing Description

### Short Description (80 characters max)
```
On-device voice-to-text dictation. No data collection, 100% private.
```

### Full Description (4000 characters)

```
Vox is a fully on-device voice dictation app that types what you say into any Android app.

PRIVACY-FIRST
All processing happens on your device. Your voice and dictated text are never uploaded or transmitted anywhere. Vox keeps a local history of your dictations in private on-device storage so you can review past takes (on by default; clear it anytime by clearing app data) — that history never leaves your phone. We have zero analytics, zero ads, and zero off-device data collection.

HOW IT WORKS
• Tap the floating Vox bubble to start dictating
• Speak naturally—Whisper.cpp (running on your GPU) transcribes in real-time
• Gemma LLM (running on your CPU) cleans up your text for perfect grammar and punctuation
• Hit the bubble again or wait for silence—your text is injected into the focused app
• No network calls. Ever. (Except the one-time model download on first launch.)

HANDS-FREE AI TEXT EDITING
Long-press the bubble to activate AI-Edit mode:
• Select any text in any app
• Speak a rewrite instruction ("Make it shorter", "Formal tone", "Fix typos")
• Your selected text is rewritten on-device and injected back instantly

CUSTOM DICTIONARY
Add your own words and corrections:
• Teach Vox your name, company, technical terms, or common mistakes
• Post-processing find/replace gives you full control over cleanup

RAW MODE
Skip the LLM cleanup for a take and inject the raw transcript instead—ideal for code, names, or precise technical language where cleanup would break things.

"SCRATCH THAT"
Just say it to discard the entire take and start over without leaving the app.

DEVICE REQUIREMENTS
Vox requires approximately 6GB of device RAM to run the on-device models efficiently. The app will refuse to run on devices below this threshold to ensure a smooth, responsive experience.

WHAT'S NOT INCLUDED
❌ Cloud accounts or sign-up
❌ Ads or ad networks
❌ Analytics or telemetry
❌ Data selling or sharing
❌ Network calls (after download)

The app is 100% yours, on your device, private by design.

ACCESSIBILITY
Vox uses AccessibilityService to locate your currently focused text field and inject your dictated text. This is the same mechanism that system keyboards and accessibility keyboards use. You control when the service is enabled and can disable it anytime in Settings.

COMPATIBILITY
Works with any Android app that has a text input field—Google Keep, Gmail, Messages, web forms in Chrome, third-party note apps, social media, email clients, and more. Uses direct text insertion, with a clipboard-paste fallback for apps that don't support it.

GET STARTED
1. Install Vox
2. Open the app and enable the AccessibilityService when prompted
3. Let Vox download its models (one-time, ~740MB total, Wi-Fi only — required, not just recommended)
4. Tap the floating bubble anywhere and start dictating
5. Your text appears instantly, perfectly cleaned up

Questions? All on-device. No servers. No servers means no downtime, no privacy concerns, just you and your words.
```

### Key Features List (as bullet points for the listing)
- 100% on-device voice dictation
- No data collection, no analytics, no ads
- Whisper.cpp speech recognition + Gemma LLM cleanup
- AI-Edit mode: rewrite selected text by voice
- Custom dictionary for names and corrections
- Raw mode for verbatim transcription
- "Scratch that" voice cancel
- Floating bubble overlay, works in any app
- Local dictation history saved privately on-device (on by default, clearable anytime)
- Requires ~6GB RAM minimum
- One-time model download (~740MB total), then fully offline

---

## Screenshot List (8–10 screenshots recommended)

| # | Screen | Description | Purpose |
|---|--------|-------------|---------|
| 1 | Main Activity (idle) | Vox floating bubble is visible on home screen, docked bottom-right. Device shows Vox is ready. | Show the core UI entry point. |
| 2 | Permission Flow | Onboarding screen: "Device capability check: 6GB RAM ✓" and "Enable AccessibilityService" button with plain-language explanation. | Demonstrate first-run safety and consent flow. |
| 3 | Recording State | Keep note open (foreground app). Vox bubble is animated (red or pulsing mic icon). Live-caption sub-bubble shows streaming ASR partial ("Hello, this is a…"). | Show the dictation experience in a real app. |
| 4 | Cleaned Result | Same Keep note, now with final injected text: "Hello, this is a voice dictation demo for Vox." Text is clean, punctuated, capitalized. | Prove that cleanup works and text lands correctly. |
| 5 | AI-Edit Mode | Another Keep note entry with selected text highlighted. Bubble is in "AI-Edit" state (blue highlight or distinct visual). Caption shows rewrite instruction ("Make it formal"). | Showcase the AI-edit feature. |
| 6 | AI-Edit Result | Same entry with selected text changed/rewritten. | Show successful rewrite injection. |
| 7 | Settings / Service Status | Vox settings screen or main activity showing "AccessibilityService: Enabled ✓" and service status summary. | Assure users about permission status and privacy. |
| 8 | Custom Dictionary | Settings screen showing a custom dictionary entry that corrects a real mis-transcription (e.g. "jemma" → "Gemma"). | Highlight personalization feature. |
| 9 | Privacy/Info Screen | App info or settings screen stating "100% on-device processing — nothing is transmitted off your device" with a note about the local dictation history and how to clear it. | Reinforce privacy messaging on store listing. |
| 10 | Multi-App Compatibility | Montage or grid showing Vox working in 3–4 different apps (Gmail, Messages, Chrome address bar, web form). | Demonstrate broad compatibility. |

**Tablet / Large-Screen Screenshots:** Recommend at least 2–3 screenshots showing the app on a larger device to cover the Google Play tablet compatibility criteria.

---

## Content Rating Questionnaire Notes

### ESRB Content Rating (Likely: E for Everyone, or E10+)

**Vox app rating considerations:**
- **Violence:** None. No violent content, graphics, or simulation.
- **Language:** None. App contains no offensive language.
- **Sexual Content:** None.
- **Substance Use:** None.
- **Gambling:** None.
- **Scary Content:** None.

**Expected answer:** Vox should rate **ESRB E for Everyone** (or E10+ if jurisdictions have specific age guidance).

### Google Play Content Rating (IARC Questionnaire)

**Answers:**
- **Does this app contain content rated for 'Mature Audiences'?** No.
- **Does this app have persistent internet connectivity requirement?** No (one-time download on first launch; offline after).
- **Does this app have user-generated content?** No.
- **Does this app have the ability to share user-generated content with other users?** No.
- **Does this app allow purchase of digital goods or services?** No.
- **Does this app have ads or in-app purchases?** No.
- **Does this app collect personal or sensitive user information?** No.

**Expected rating:** Vox should receive the least restrictive rating category (All Ages / Everyone).

---

## Pre-Submission Deploy-Time Checklist

**Status:** Code complete (Tasks 1–13). Deployment items below must be finalized before release.

### Model Hosting

- [ ] **Whisper Model Hosting URL**
  - Model: `ggml-small-q5_1.bin` (~190MB)
  - Hosting provider: (Choose one)
    - [ ] Hugging Face (free, public model repo)
    - [ ] GitHub Releases (free, but size-limited to 2GB per file)
    - [ ] AWS S3 / Google Cloud Storage (paid, but scalable for high download volume)
    - [ ] Firebase Cloud Storage (free tier available)
  - URL format must support HTTP range requests for resumable downloads
  - **Action:** Upload model, generate direct download URL, test resumability
  - **URL:** `[INSERT URL]`

- [ ] **Gemma Model Hosting URL**
  - Model: `gemma3-1b-it-int4.task` (~550MB, MediaPipe format)
  - Same hosting provider as Whisper (keep URLs consistent)
  - **Action:** Upload model, generate direct download URL, test resumability
  - **URL:** `[INSERT URL]`

### Signed Release Build

- [ ] **Generate Signing Key**
  - Create a keystore file (if not already created for a prior Vox build)
  - Store keystore **outside** the repo (e.g. `~/.android/vox.keystore`)
  - Document the keystore path and password in a secure location (NOT in git)
  - **Action:** Run `keytool -genkey -v -keystore ~/.android/vox.keystore ...`

- [ ] **Build Signed APK/AAB**
  - Run: `./gradlew bundleRelease` (generates Android App Bundle for Play)
  - Sign with keystore from above
  - Verify signature: `jarsigner -verify -certs build/outputs/bundle/release/app-release.aab`
  - **Output file:** `build/outputs/bundle/release/app-release.aab`

- [ ] **Test Signed Build on Device**
  - Upload signed bundle to Play Console internal testing track
  - Install on test device (Samsung S24 Ultra + floor-spec device)
  - Run full flow: device check → onboarding → model download → dictate → inject → AI-edit
  - Confirm all telemetry and analytics are disabled
  - **Action:** Device testing complete, screenshot results

### Play Console Setup

- [ ] **Create App Listing**
  - App name: "Vox — On-Device Voice Dictation"
  - Package: com.hadencain.vox
  - Paste descriptions, screenshots, and privacy policy (from sections above)

- [ ] **Configure Store Listing**
  - [ ] Full description (from Store Listing Description section above)
  - [ ] Short description (from Store Listing Description section above)
  - [ ] Screenshots (8–10 from Screenshot List above)
  - [ ] Feature image (if required)
  - [ ] Upload demo video (from accessibility-declaration.md shot list)
  - [ ] Set category: Productivity or Utilities
  - [ ] Content rating: E for Everyone (from questionnaire above)

- [ ] **Privacy and Security**
  - [ ] Link privacy policy (paste or external URL)
  - [ ] Complete Data Safety questionnaire (answers in Data Safety Form section above)
  - [ ] Declare AccessibilityService use (from accessibility-declaration.md)
  - [ ] Upload demo video for accessibility use (if Play requests)

- [ ] **Pricing and Distribution**
  - [ ] Set price: Free
  - [ ] Select countries/regions for distribution (all, or targeted list)
  - [ ] Confirm device requirements: minSdk 33, ~6GB RAM gate (enforced in-app)

- [ ] **Upload Release Build**
  - [ ] Upload signed AAB to Release track (not internal testing)
  - [ ] Add release notes: "Vox v1.0 — 100% on-device voice dictation with AI-edit mode"
  - [ ] Confirm build is signed and ready for submission

### Final Review Before Publication

- [ ] **Accessibility declaration** approved by Play (no rejections from human review)
- [ ] **All screenshots** display correctly and match the product
- [ ] **Privacy policy** is clear, accurate, and hosted (or copy-pasted into Play Console)
- [ ] **Data Safety form** is complete and matches the privacy policy
- [ ] **Demo video** is uploaded and plays correctly on Play Console preview
- [ ] **Signed build** has been tested on real devices and functions end-to-end
- [ ] **Model URLs** are live, tested for resumable download, and included in app code
- [ ] **Onboarding flow** (device check → permissions → model download) is smooth
- [ ] **Commit SHA** of the release build is recorded for release notes

### Post-Publication

- [ ] Monitor Play Console review status (human review typically takes 2–7 days)
- [ ] Monitor crash/ANR rates and reviews on launch
- [ ] Be ready to address Play team feedback or rejection reasons
- [ ] Plan v1.1 (minor fixes) and v2 (new features, e.g. multi-language, history mining)

---

## Notes

- **RAM gate is in-app enforcement, not store-filterable.** The Play Store does not expose a RAM minimum filter. Vox checks device RAM on launch and refuses to run if below ~6GB. Document this in the store description so users understand before downloading.
- **First-run model download is the only network activity.** After models are cached on-device, Vox runs 100% offline. Emphasize this in the privacy policy and listing.
- **Accessibility Service is required but must be user-granted.** Vox does not enable it automatically; the user must open Settings and enable it explicitly. The app explains why before requesting.
- **Demo video is critical for Play approval.** The shot list in accessibility-declaration.md should be filmed and uploaded before submitting the first build.

