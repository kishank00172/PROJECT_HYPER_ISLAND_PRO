# HYPER ISLAND PRO — Phase 0 Foundation

This is the first clean foundation build for **HYPER ISLAND PRO**.

## Locked decisions

- Language: **Kotlin**
- UI: **XML layouts**
- Assets: **XML drawables/vectors only**
- Min SDK: **API 33**
- Package: `com.hyperisland.pro`
- No fake punch-hole/cutout drawing. The island will be rendered as a flat AMOLED black surface in later phases.
- No fake feature toggles. If an engine is not implemented yet, UI clearly says it is coming later.

## What Phase 0 contains

- Clean Android project structure
- Main screen
- Permission Doctor screen
- Test Lab placeholder screen
- Settings screen with developer mode preference
- Placeholder service declarations for later phases:
  - Island overlay service
  - Notification listener service
  - Accessibility service
- GitHub Actions workflow to build debug APK

## What Phase 0 intentionally does NOT contain

- No floating island overlay yet
- No fake notification island
- No fake music/call/download/timer states
- No Firebase yet
- No Shizuku integration yet

## Build on GitHub

1. Create a new GitHub repository.
2. Upload/push all files from this folder to the repository root.
3. Open the **Actions** tab.
4. Run **Android Debug APK** workflow manually, or push to `main`.
5. Download the generated APK artifact.

## Phase 0 phone test checklist

After installing the APK:

- App opens without crash
- Home screen shows `Phase 0 — Foundation`
- Permission Doctor opens
- Overlay permission Fix button opens correct settings
- Notification access Fix button opens correct settings
- Accessibility Fix button opens correct settings
- Battery optimization Fix button opens correct settings or fallback settings
- Test Lab clearly says real engines will come later
- Settings developer mode toggle saves state

## Next phase

**Phase 1 — Real AMOLED black pill overlay using WindowManager**

Phase 1 will add:

- Real overlay service
- Pure black pill `#000000`
- Top-center position
- Show/hide toggle
- Basic X/Y/width/height settings
- No fake cutout drawing

## Dev flow — locked 2026-09-18

**One branch: `main`.** No feature branches for routine work. This repo has 0 merge commits in its
whole history and every fix so far went straight to `main`; branches only added cost here.

- Push to `main` → CI (`Android Debug APK`) builds and **publishes a release asset**: tag `ci-<run>`,
  file `app-debug.apk`. No GitHub artifact quota needed, login-in-browser download link:
  `https://github.com/kishank00172/PROJECT_HYPER_ISLAND_PRO/releases/download/ci-<run>/app-debug.apk`
- `versionName` carries the run: `0.3.6-phase3.6+b<run>` — check what is actually on the phone.
- All builds are signed with `keystore/hyperisland-test.jks`, so a new APK **installs over** the old
  one. No uninstall, permissions keep their grants.
- Restore point = the **`last-good` tag**, not a branch. Move it forward whenever you have verified a
  build on-device. Rollback = `git revert <bad-sha>` on `main` (never force-push `main`).
- **Only exception** that earns a short-lived branch (delete within 24h): work CI cannot judge because
  it is runtime/ROM behaviour — the reflection touch-region hack, manifest permission changes, keystore
  rotation, anything that could brick boot. Reason: a green build proves compilation, not the device.
- `ci-<run>` tags accumulate; delete the old ones you no longer need (keep the one pointed at by
  `last-good`).
