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

## Current position - updated 2026-09-21 (read me first if you lost the plot)

`main` head carries the **b1350** round (release `ci-350`, commit `b73d0ec`, 58 JVM tests green);
`last-good` still points at `ff98959` (release `ci-314`) on purpose: the morph feel, the swipe feel and the
sender-name fix are all waiting for an on-device verdict from the only tester we have. When a build is
called good, move `last-good` to it.

b1350 = the morph's cost per frame. Tapping the pill to expand dropped frames and the card text visibly
"settled" 2-3 frames before the card was full size. `updateIslandLayout` ran on every frame of every morph
and (a) assigned `layoutParams` even when `lerpEven` had produced the *same* size, i.e. a redundant
requestLayout traversal of a screen-sized overlay exactly in the slow-out tail, (b) invalidated the root's
outline instead of the card's, and (c) left `gridRoot`/`pillPreviewRoot` at MATCH_PARENT in a box whose
height is animating, so the text was re-centred every frame - that is the slide he called a glitch. Morph
frames now go through `updateIslandLayoutForMorph` (radius always, size only when it moved), the outline
belongs to the card, and for the duration of a morph the two columns are pinned to their own height with a
centering gravity plus a hardware layer on the text column. Separately, `setStageAnimated` had a **second**
animator on `pillPreviewRoot.alpha` racing the morph's own per-frame `1f - t` - the pop at 140 ms.
`core/MorphJank.kt` times every frame and the trace log prints
`[MORPH] end stage->STAGE3_FULL frames=21 avg=17ms max=48ms@t=0.92 slow=3`, so the next "lagdu lagta hai" is
a number and the remaining suspect (`clipToOutline` + animated radius rebuilding the clip mask per frame,
fixable only by the P0-3 full-size-window change) gets proved or cleared before anyone rewrites it.

b1343 = what the card is *headed with*. In a 1:1 the name comes from the newest message's sender, not from
`android.conversationTitle`: Instagram writes the owner's own handle into that field for a 1:1, so his
username printed where `Meta AI` belonged (`uploads/noti.txt` seq 335 - conversationTitle='kish.ank001',
messages[0].sender='Meta AI', isGroupConversation=false). Groups keep the thread name; that one boolean
picks the branch. "Me" is `android.selfDisplayName` equality **or** the message's `sender_person` key equal
to `android.messagingUser`'s key, because a username is not a display name and neither is stable. Chat
previews fold onto one line, which is what removes the stray ellipsis a blank line inside a message produced
under `maxLines=2` + ellipsize. And the trace log prints the rule that fired (`rule=1to1:sender`), so the
next wrong name is a line in the file instead of another rebuild.

b1338 = the swipe now shows the incoming page *while the finger is moving* (startRingPush at the moment
the drag latches, both pages driven in lock-step, one curve, one duration; commit needs 18% of the card
width instead of 24%). Before this, dragging moved only the content inside a stationary pill background and
the neighbour was created at release - so the side you dragged towards was empty, which is what "jagah khali
hai" meant. Ring edge / no-neighbour drags get a capped 18dp nudge instead of an empty band.

b1336 = a page push could strand the card content one card-width off the pill (its reset lived in
`withEndAction`, which `gridRoot?.animate()?.cancel()` skips) — that blank card, and the swipe-ignoring
`ringSwapInFlight` that followed it, are why `endRingSwap(reason)` now owns that state with an
uncancellable guard. And there is an in-app trace log (main screen -> TRACE LOG, mirrored to
`adb logcat -s HIP_TRACE`, persisted so a service kill does not eat it): "kuchh nahi hua" now has to say
which branch ran instead of being guessed at.

b1333 = touch + swipe feel + the pill counter: `core/IslandGesture.kt` latches drag-vs-tap and the card
now rides under the finger (commit settles both layers on one curve, no teleport); the ring holds 24
chats instead of my old 5 and logs every eviction; the pill badge counts **conversations**, not a sum of
app badges (the old sum double-counted WhatsApp's group summary and stuck at 99 because Telegram's badge
said 946536). Group summaries are refused at the extractor.

b1330 = the four symptoms reported on b1325 (own name on a card, headline "Instagram", old messages
stamped "now", no quick actions). Cause: we printed EXTRA_TITLE (which on Instagram means *last
sender*) instead of reading `android.conversationTitle` + `android.selfDisplayName` + `Message.time`,
and a second ingestion path inside the accessibility service kept producing degraded cards. That path is
**deleted** — notification ingestion is the NotificationListenerService alone; accessibility stays for
typing replies and touch handling. Display rules live in `core/ChatDisplayPolicy.kt` with 18 JVM tests
against real capture values (58 tests in CI total: 18 display + 15 identity + 14 gesture + 5 trace log + 6 morph jank), and the rule is: **a group is named by which thread, a
1:1 by who spoke** - `android.isGroupConversation` decides which branch runs. That reversal is b1343's fix:
in a 1:1 with Meta AI,
Instagram put the owner's own handle in `android.conversationTitle`, and trusting the thread name printed
`kish.ank001` as the headline of a message from Meta AI. "Me" is now proven by `android.selfDisplayName` *or*
by matching the message's `sender_person` key against `android.messagingUser`'s key; a card with no sender
recovers the speaker from IG's `"<thread>: <sender>"` title; a preview is folded onto one line so a blank line
inside a message stops rendering as a stray ellipsis. The trace log prints which rule fired (`rule=1to1:sender`).

How we decide things now (this rule exists because two guesses turned out wrong):
**measure before fixing.** `kishank00172/notification-lab` is a second app that dumps real
notifications from this phone as JSONL; `Exports/notification_raw.jsonl (2)` (99 records) is the source
the last round was built on. `python3 tools/notiflab.py summary|keys <file>` reads a capture. Anything
about *feel* still can only be judged on the device, so it waits for the tester instead of for me to
guess twice.

Waiting on-device verdict for `ci-325` / `b1325`:
1. Tap one card in the expanded island → it opens, **that card is discarded, the others stay visible**
   (used to hide the whole island, badge included, while the read chat came back later).
2. 3 chats at once → `1/3`; 3 messages from one chat → one card with a growing count; two chats with
   the same display name → two cards (`shortcutId` decides, tested in CI).
3. Instagram/WhatsApp DMs after the service was killed → the re-sync now finds WhatsApp chats at all
   (their notifications carry no `category`; only their bundle summary does).
4. A burst of Snapchat promos must not push real chats out of the 5-slot ring.
5. Read a chat in-app → its card should leave the island ~1.5 s later (the app cancels its own
   notification, reason 8; the delay is what stops the card blinking out mid-post).

Any message that vanishes must now say why: `adb logcat -s HIP_TRACE HyperEchoSuppressor` →
`DROP <reason>` / `SHOW … identity=<tier>` / `REMOVE reason=…` / `DISMISS dropped 1 page`.

Open, in order of what the data says is worth it: Test Lab should surface the drop reasons
(`lastDebugMessage` is already written and never shown); P0-3 island-sized window +
`FLAG_WATCH_OUTSIDE_TOUCH` replacing the reflection inset hack; P0-4 drop
`GLOBAL_ACTION_SHOW_KEYBOARD = 16`; the direct-reply experiment (Instagram's reply action is a **mutable
broadcast** PendingIntent with `resultKey=DirectNotificationConstants.DirectReply`, so `send()` +
`RemoteInput.addResultsToIntent` may replace the accessibility typing hack — runtime-only, needs a
flag in Test Lab); avatars (`person.icon` is a 175×175 bitmap, `shortcutInfo.extras.imageHash` is a real
cache key); `values*/strings.xml` still says "Phase 0" — a stale trap, not a bug.

### APK delivery — locked 2026-09-19 (tester asked for links, not files)

The build is handed over as a **download link**, and `/home/user` keeps **at most one** APK (the build
being tested); it gets deleted as soon as he says the install happened. Do not let the workspace fill up
with `hip-build-*.apk` files.

Mirror hosts, as measured from the sandbox (this saves the next session from re-discovering it):
- **tmpfiles.org** — works, no account: `curl -F "file=@app-debug.apk" https://tmpfiles.org/api/v1/upload`
  returns a page URL; the page's real href is `…/dl/<token>/<id>/<name>` and that serves raw bytes.
  **Files die in 60 minutes**, so always also give the GitHub release link.
- catbox.moe — `Invalid uploader` for every anonymous API upload from here (datacenter IP); litterbox 500s.
- uguu.se — `415 Filetype not allowed` for `.apk` (and 3 h retention). 0x0.st — uploads disabled.
  pixeldrain — API needs a key. So: tmpfiles + the release link, nothing fancier.

Always verify a mirror before handing it over: download it back and compare sha256 with the release
asset — an accepted upload is not a working download. GitHub release assets stay the durable source:
`releases/download/ci-<run>/app-debug.apk`.
