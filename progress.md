# Progress

## Completed

- Renamed the product to **Call Trace** across app label, dashboard, notifications, dialogs, and service text.
- Added real-time call monitoring through `CallDefenderService` and `CallReceiver`.
- Added contact whitelist logic so saved contacts skip scam analysis.
- Added call bomber detection for repeated calls from the same number.
- Added known scam number lookup through Firebase/Firestore.
- Added peak vibration warnings for risky live calls.
- Added local scam keyword and pattern analysis.
- Switched the user-facing AI setup to NVIDIA-only keys.
- Added separate NVIDIA LLM and NVIDIA ASR key inputs.
- Added NVIDIA cloud analysis for live transcripts using NVIDIA chat completions.
- Added NVIDIA ASR upload flow for audio-file transcription before scam analysis.
- Added Firestore call history persistence.
- Added Firestore blocked number persistence.
- Added call history screen, filters, and CSV export.
- Added SMS scam/OTP quarantine logic.
- Added Firebase Auth login flows.
- Added profile and country selection.
- Added PWA dashboard prototype.
- Added Firebase Data Connect schema and generated Android connector files.
- Added root `README.md` with setup, build, Firebase, and testing notes.

## In Progress / Next

- Test uploaded audio transcription with the user's NVIDIA ASR account limits and supported formats.
- Add a backend proxy only if the NVIDIA ASR account requires server-side mediation.
- Improve live call transcript reliability across Android devices.
- Add automated instrumentation tests for call state transitions.
- Add safer sample Firebase rules for production use.
- Add release build signing setup outside source control.

## Verified

Android debug build was verified with:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Notes

- `google-services.json` is intentionally ignored and must be supplied locally.
- NVIDIA LLM and NVIDIA ASR keys are stored separately on device.
- Uploaded audio analysis now sends audio for NVIDIA ASR, then sends the transcript through NVIDIA LLM scam analysis.
