# Progress

## Completed

- Renamed the product to **Call Trace** across app label, dashboard, notifications, dialogs, and service text.
- Added real-time call monitoring service with phone state receiver.
- Added contact whitelist so saved contacts skip scam analysis.
- Added call bomber detection for repeated calls from the same number.
- Added known scam number lookup through Firestore/Firebase client.
- Added peak vibration alert pattern for risky live calls.
- Added local keyword and pattern scam analysis.
- Added Gemini cloud analysis for live transcript and uploaded audio.
- Added NVIDIA cloud analysis for live transcripts using chat completions.
- Added Firestore call history persistence.
- Added Firestore blocked number persistence.
- Added call history screen, filters, and CSV export.
- Added SMS quarantine detection for scam/OTP messages.
- Added Firebase Auth login flows.
- Added profile and country selection.
- Added PWA dashboard prototype.
- Added Firebase Data Connect schema and generated connector files.

## In Progress / Next

- Add NVIDIA Riva/ASR transcription for uploaded audio files.
- Add a backend proxy for NVIDIA audio transcription if direct Android gRPC becomes too heavy.
- Improve live call transcript reliability across devices.
- Add automated instrumentation tests for call state transitions.
- Add safer sample Firebase rules for production use.
- Add release build signing setup outside source control.

## Verified

- Android debug build was verified with:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Notes

- `google-services.json` is intentionally ignored and must be supplied locally.
- NVIDIA API keys starting with `nvapi-` are detected as NVIDIA keys.
- Gemini is still required for uploaded audio transcription until NVIDIA Riva/ASR is implemented.
