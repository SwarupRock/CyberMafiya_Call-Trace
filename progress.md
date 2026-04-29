# Progress

## Completed

- Renamed the product to **Call Trace** across app label, dashboard, notifications, dialogs, and service text.
- Added real-time call monitoring through `CallDefenderService` and `CallReceiver`.
- Added contact whitelist logic so saved contacts skip scam analysis.
- Added call bomber detection for repeated calls from the same number.
- Added known scam number lookup through Firebase/Firestore.
- Added peak vibration warnings for risky live calls.
- Changed live scam alerts to a three-pulse cut-call vibration pattern.
- Added local scam keyword and pattern analysis.
- Added benign-conversation guardrails to reduce false positives for school, work, family, friend, and scheduling calls.
- Added repeated NVIDIA cloud analysis throttling so longer live transcripts can be rechecked without flooding the API.
- Switched the user-facing AI setup to NVIDIA-only keys.
- Added separate NVIDIA LLM and NVIDIA ASR key inputs.
- Added per-user NVIDIA key sync through private Firestore settings.
- Added NVIDIA cloud analysis for live transcripts using NVIDIA chat completions.
- Added NVIDIA Riva/Parakeet gRPC upload flow for audio-file transcription before scam analysis.
- Added live microphone chunk transcription through NVIDIA ASR when an ASR key is configured.
- Added Android speech-recognition fallback and user-visible status messages when raw call audio is blocked.
- Added speakerphone routing request during active calls and audio route restore after calls.
- Added Android audio decoding and 16 kHz mono PCM conversion before sending files to NVIDIA ASR.
- Added Firestore call history persistence.
- Added Firestore blocked number persistence.
- Added Firestore CSV backups for saved call logs and uploaded-audio analysis.
- Added call history screen, filters, CSV export, and optional NVIDIA translation for Kannada, Hindi, Telugu, and Tamil.
- Added SMS scam/OTP quarantine logic.
- Added Firebase Auth login flows.
- Added profile and country selection.
- Added PWA dashboard prototype.
- Added Firebase Data Connect schema and generated Android connector files.
- Added root `README.md` with setup, build, Firebase, and testing notes.
- Tightened Firestore rules so user subcollections are owner-only and unmatched paths are denied.

## In Progress / Next

- Test live NVIDIA ASR and uploaded audio transcription with the user's NVIDIA ASR account limits.
- Add a backend proxy only if the NVIDIA ASR account requires server-side mediation.
- Improve live call transcript reliability across Android devices.
- Add automated instrumentation tests for call state transitions.
- Add Firestore indexes only if history filtering/query patterns require them.
- Add release build signing setup outside source control.

## Verified

Android debug build was verified with:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Notes

- `google-services.json` is intentionally ignored and must be supplied locally.
- NVIDIA LLM and NVIDIA ASR keys are stored separately on device.
- NVIDIA keys can also sync to the authenticated user's private Firestore settings document.
- Uploaded audio analysis now sends audio for NVIDIA ASR, then sends the transcript through NVIDIA LLM scam analysis.
- Live transcription may require speakerphone because Android and OEM privacy rules can block direct call audio capture.
