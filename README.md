# Call Trace

Call Trace is an Android project that helps record, analyze and trace suspicious incoming calls to assist users and investigators in identifying scam attempts. The app started from a previous codebase named "Scam Shield AI" and has been refactored and renamed for this event.

## What this repo contains
- Android app source under `app/`.
- Firebase configuration and deployment files: `firebase.json`, `firestore.rules`, `firestore.indexes.json`.
- Documentation: `README.md`, `progress.md`.

## Key features (current)
- App display name set to **Call Trace** (`app/src/main/res/values/strings.xml`).
- Firebase authentication referenced in code (`old_login.java`) with Google/Phone sign-in flows.
- Firestore configured as primary database; basic security rules require authentication to read/write.

## Firebase / Database notes
- Firestore is configured in `firebase.json` with location `us-central1` and uses `firestore.rules` and `firestore.indexes.json`.
- Current Firestore rule (see `firestore.rules`) allows read/write only for authenticated users: `request.auth != null`.
- `google-services.json` is present locally for app builds but is listed in `.gitignore` and should not be committed to the public repo.

## Progress tracking and governance (required for event)
- Repository naming, visibility, commit cadence, contributor rules, and progress tracking are enforced in this repo. See `progress.md` for log entries and update instructions.

## Setup (local developer)
1. Clone the repository:

```bash
git clone <your-remote-url> TeamName_CallTrace
cd TeamName_CallTrace
```

2. Connect Firebase (local only): place your `google-services.json` in `app/` (do not commit it), then build with Android Studio or Gradle.

3. Commit & push changes regularly. For event compliance, push meaningful commits at least once every 3 hours and record matching entries in `progress.md`.

## Contribution & rules summary
- Repo must be public during evaluation. Name format: `TeamName_ProjectName`.
- Add all team members and event organizers as collaborators.
- Do NOT copy other teams' code, fork competing repos, or upload pre-built projects as new work.
- Keep repo lightweight; do not include large datasets/videos (use external storage links).

## Files changed locally during initial migration
- `app/src/main/res/values/strings.xml` — set `app_name` to `Call Trace`.
- `README.md` — this file (updated).
- `progress.md` — created and populated with templates and current progress.

## Next steps (recommended)
- Commit and push these changes to the project's GitHub remote.
- In Firebase Console: verify the linked project, enable Auth providers (Google and Phone), and confirm Firestore settings and rules.
- Invite collaborators via GitHub Settings and begin regular 3-hour commits.

If you want, I can also prepare a short Firebase Console checklist or generate a set of example `progress.md` entries for the next 24 hours.