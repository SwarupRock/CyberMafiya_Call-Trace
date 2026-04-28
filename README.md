# Call Trace

Call Trace is an Android-first scam call defense app built to warn users during suspicious calls. It combines live call state monitoring, speech-to-text, local scam pattern detection, cloud AI analysis, Firebase-backed history, SMS quarantine, and strong vibration alerts.

## What We Built

- Android app branded as **Call Trace**
- Firebase Authentication login with email, Google, phone OTP, and demo flow
- Live phone call monitoring through a foreground service
- Unknown caller screening with contact whitelist support
- Real-time speech-to-text during active calls
- Local scam intelligence for OTP, KYC, banking, urgency, authority, lottery, tech support, threat, refund, and credential patterns
- Firebase scam number lookup from `scam_numbers`
- Call bomber detection for repeated calls from the same number
- Peak vibration warning during risky live calls
- Auto-block flow for confirmed scam calls where Android permissions allow it
- Firestore call history with transcript, score, verdict, threat type, and blocked state
- Blocked-number history in Firestore
- Call history screen with filters and CSV export
- SMS scam/OTP quarantine logic
- Audio file upload analysis through Gemini
- NVIDIA API support for live transcript analysis through NVIDIA chat completions
- PWA dashboard prototype assets
- Firebase Data Connect schema and generated Android connector files

## AI Providers

Call Trace supports two cloud AI modes:

- **Gemini**: live transcript analysis and uploaded audio transcription/analysis.
- **NVIDIA**: live transcript analysis using NVIDIA's OpenAI-compatible chat completions endpoint.

NVIDIA chat models do not transcribe uploaded audio files directly. NVIDIA audio transcription requires NVIDIA Riva/ASR, which is a separate gRPC API. That is tracked as a next step.

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
- `scam_numbers/{phoneNumber}`

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

- Live speech-to-text depends on Android speech recognition and microphone/call audio availability.
- NVIDIA support currently analyzes text transcripts only.
- Uploaded audio transcription with NVIDIA requires adding NVIDIA Riva/ASR.
- Some Android call blocking APIs depend on device permissions, default dialer restrictions, and OEM behavior.
