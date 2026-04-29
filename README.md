# Call Trace

Call Trace is an Android-first scam call defense app built to warn users during suspicious calls. It combines live call state monitoring, speech-to-text, local scam pattern detection, cloud AI analysis, Firebase-backed history, SMS quarantine, and strong vibration alerts.

## What We Built

- Android app branded as **Call Trace**
- Firebase Authentication login with email, Google, phone OTP, and demo flow
- Live phone call monitoring through a foreground service
- Unknown caller screening with contact whitelist support
- Real-time speech-to-text during active calls
- Live microphone transcription with NVIDIA ASR fallback when configured
- Speakerphone routing/status guidance for Android devices that restrict call audio capture
- Local scam intelligence for OTP, KYC, banking, urgency, authority, lottery, tech support, threat, refund, and credential patterns
- Benign-conversation guardrails to reduce false positives for school, work, family, and scheduling calls
- Firebase scam number lookup from `scam_numbers`
- Call bomber detection for repeated calls from the same number
- Three-pulse vibration warning during risky live calls
- Auto-block flow for confirmed scam calls where Android permissions allow it
- Firestore call history with transcript, score, verdict, threat type, and blocked state
- Blocked-number history in Firestore
- Call history screen with filters, CSV export, and optional NVIDIA-powered transcript translation
- Firestore CSV backups for saved call and uploaded-audio analysis records
- SMS scam/OTP quarantine logic
- Per-user NVIDIA key sync through private Firestore settings
- NVIDIA API support for live transcript analysis through NVIDIA chat completions
- NVIDIA Riva/Parakeet ASR support for uploaded audio transcription before scam analysis
- NVIDIA Riva ASR support for live 16 kHz mono PCM chunks when an ASR key is available
- PWA dashboard prototype assets
- Firebase Data Connect schema and generated Android connector files

## AI Providers

Call Trace uses NVIDIA APIs only:

- **NVIDIA LLM API key**: live transcript scam analysis using NVIDIA's OpenAI-compatible chat completions endpoint.
- **NVIDIA ASR API key**: uploaded audio transcription through NVIDIA Riva/Parakeet gRPC before the transcript is checked by the LLM.

NVIDIA chat models do not transcribe uploaded audio files directly, so Call Trace keeps two separate NVIDIA keys: one for text intelligence and one for speech-to-text.

## Firebase Setup

This repo intentionally does not commit `google-services.json`. To run the Android app:

1. Create or open your Firebase project.
2. Add an Android app with package name `com.scamshield.defender`.
3. Download `google-services.json`.
4. Place it in the project root or configure it as expected by your Gradle setup.
5. Enable Firebase Auth providers and Firestore.

Expected Firestore collections include:

- `users/{uid}/call_history`
- `users/{uid}/blocked_numbers`
- `users/{uid}/csv_backups`
- `users/{uid}/private_settings/nvidia_keys`
- `scam_numbers/{phoneNumber}`

The included Firestore rules restrict user data to the authenticated owner, allow authenticated reads from `scam_numbers`, and deny all other unmatched paths.

Example scam number document:

```text
scam_numbers/5551234
totalReports: 2
avgScamScore: 0.95
isVerifiedScam: true
```

## Testing Scam Alerts

Use an emulator to simulate calls:

```powershell
adb emu gsm call 5551234
adb emu gsm cancel 5551234
```

Repeat the same call 3 times within 5 minutes to trigger call bomber detection.

To test database detection, create a matching document in `scam_numbers` with at least `totalReports: 2`.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Current Limitations

- Live speech-to-text depends on Android speech recognition, microphone/call audio availability, and device call-audio privacy rules.
- Some phones require in-call speakerphone for live transcription because Android may not expose raw call audio.
- NVIDIA live analysis works on text transcripts.
- Uploaded audio transcription depends on the NVIDIA Riva/Parakeet function being available for the key/account used on the device.
- Some Android call blocking APIs depend on device permissions, default dialer restrictions, and OEM behavior.
