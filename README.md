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

`main` head carries the **b1378** round (release `ci-378`, commit `5d54874`, 96 JVM tests green), which is what
he had been describing since round 16 and I kept reading as a curve problem: with the pinned-view design the card
is laid out once at its FINAL size, so the row parks at the left edge of the final box while the drawn box starts
pill-sized and centred - opening the island was literally a mask widening over a still picture. Now the row is
pinned to the box's left inner edge every frame (`gridRoot.translationX = boxLeft`, self-cancelling at full
width, render transform only, so the width lock that ended the text jitter still holds), and the icon is one
travelling element: pill slot to card slot, 32dp to 38dp, and it wears the pill's glyph until half-way, then the
launcher badge - in direction order, so a collapse never flashes a monochrome dot. `[MORPH] start` now logs
`carry=on/off iconRide=on|n/a`, because three rounds in a row have ended with me having assumed a thing was
armed. `MorphCarryTest` caught one real bug (collapse glyph on the wrong half) before it shipped.

Before that, the **b1376** round (release `ci-376`, commits `b751a6d`..`34bf2ea`, 92 JVM tests green).
His verdict on b1373 - "collapse abhi bhi first step to final step pe ja rha hai" - plus `frames=39 hz=120` on
that same collapse is the whole story: the frames were being delivered, and every one of them drew the same
box, because a morph pins the view at its **target** size and `IslandMorphFrame.compute` clamps each frame into
the target's bounds. Harmless when growing (every intermediate is smaller), fatal when shrinking (every
intermediate is larger, so all of them clamp to the pill). The bound is now the surface being drawn on,
`max(start, target)` per axis, with the true final size applied at the settle; `IslandMorphFrameTest` keeps the
wrong bound in the test on purpose, asserting that it collapses every width onto one number, so the clamp can
never again be "simplified" back into charge of the animation. Same round also stopped the tooling from eating
what it measures: 91 of 277 stall samples in his export were the trace screen's own `render()` pushing 1500
lines into a TextView every 700 ms (now a 220-line tail on screen, whole buffer in the export), and the label
lookup was two binder calls per notification in two different classes (now memoised per package, failures not
cached). `last-good` stays `ff98959`; the text-jitter report stays open.

Before that, the **b1373** round (release `ci-373`, commits `c20cfb9`..`33d3f01`, 90 JVM tests green).
It is the round the b1369 sampler was built for, and his export decided every part of it:

- **What his file proved:** 14 of 16 windows read `hz=120 gap=8ms` (the panel delivered), 6 of 21 morphs still
  starved to `frames=4 avg=95ms`; `gc=+0/0` on 20 of 21 (GC theory dead); one `[DISPLAY]` line (rate-flip theory
  dead for that session). 904 sampled main-thread blocks, 154.7 s in 21.7 min, headed by `transactNative`
  23.3 s (an uncached `getApplicationIcon` per update), `nGetFontMetricsInt` 16.7 s and `nBuildMeasuredText`
  12.0 s (the 3-span title re-measured on every update) - against `draw=0.2ms`. The frames were available; we
  were busy with text. So: `UpdateGate` skips the work when nothing changed (icon per package, text by length
  then content, tiles only when the `Action` *instances* differ) and the stall line now prints the first frame
  that is ours (`| us: updateNotificationContent@…:1184`) so the next round has a call site, not a theory.
- **What his words proved about the motion**, both fixed as separate revertable commits: every fade branch was
  gated `&& notificationMode`, so a tap-expand ran **no fade at all** - his settle line reads
  `end-state size=366x104 clip=true grid=V/1.0`, opaque content and then `GONE` one frame later, i.e.
  "ek jhatke se sidha pill"; and the size curves wasted their frames (overshoot deleted by `IslandMorphFrame`'s
  clamp = 29 of 46 frames moving 0.0% = "masking"; collapse dwell 0.1-0.7% then one 10.1% lurch). Both
  directions now move 4-5% of the distance on **every** frame at 120 Hz, durations untouched - his
  "120 fps matlab 120 chhote frames, 60 ko double karke 2x chalana nahi" said it better than my log did.
- **Dropped on evidence:** the two-window split in `STEP3-PLAN.md`. `regions=` across 21 morphs is 1-7 pokes,
  not a storm, so it would have been a rewrite for nothing. `last-good` stays `ff98959`.

Before that, the **b1369** round (release `ci-369`, commits `3c9c955`..`d2274e6`, 81 JVM tests green):
a measurement round, decided by the tester's own export of b1364 and by a question he had asked twice -
"log mei daalte ho ki ye maapo to sirf wahi sab ka deta hai? Ya saare he process dekhta hai? … aisa na ho
tumne miss kar diya kuchh". He was right that the log only ever saw the lines I chose to write at the decision
points I predicted, and his file showed the price of that: our draw was `0.1 ms` a frame while the expand that
felt like "masking lag rha hai" measured `frames=2 avg=225ms max=448ms` - something else held the main thread
for 448 ms and no instrument in the app could name it.

So the whole main thread is now watched, from outside my predictions: a daemon thread ticks the main looper
every 12 ms, and when a tick waits over 24 ms it samples the main thread's stack and logs `[STALL] 448ms
RUNNABLE :: performLayout@ViewRootImpl.java:2100 <- …`, one line per block, written when the block *ends* so
the duration is the real one (`MessageQueue.next`/`nativePollOnce` at the top with a `WAITING` thread means
descheduled, not busy - a different verdict for the same jank; over 15 s is the OEM freezing us and is
dropped). Each `[FRAME]`/`[MORPH]` window now carries `stalls=N/Xms`, so "our animation is bad" and "someone
held the thread while it ran" are different numbers. Per morph, `gc=+2/30 blocking=+2/43 alloc=3.0MB` comes
from `Debug.getRuntimeStat` (blocking GC parks the main thread; identical symptom, opposite cure).
`DisplayManager.DisplayListener` logs `[DISPLAY] panel rate 60 -> 120 hz (…)` - his log had `hz=120`, `90`,
`72`, `60` on consecutive morph lines with nothing else changing, i.e. the panel rescales under the animation,
which costs frames however cheap our draw is. FrameWatch adds per-frame cadence buckets `at=120:28,60:4` and
the animation's first gaps `lead=16/8/8` (a median of frames spread across 8 and 16 ms snaps to a rate the
panel never ran), and `regions=N` counts the touchable-region pokes - the number that either justifies the
two-window split in `STEP3-PLAN.md` or kills the idea. The split itself is still not built.

It also fixes an instrument I shipped wrong in b1364: `midMorphLayouts` was not reset when a window began, so
his log printed `layouts=1/62` - a mid count larger than the total, a lifetime sum wearing a per-window label.
Three tests pin the reset, the cadence buckets and the empty-window shape; the stall/GC formatters are pure
and tested. Two gaps are named rather than papered over: `Looper.setMessageLogging` (the tidier hook) is not
in the SDK - CI proved it, run 366, unresolved reference - and per-view RenderThread/GPU timing needs
`View.addFrameMetricsListener`, which is `@hide` in AOSP. Both need adb/perfetto on his phone.

**No animation behaviour changed in b1369, deliberately.** His log had already cleared one of his three
reports by itself: every `end-state` line at a settle reads `clip=true grid=V/0.0 pill=V/1.0`, so the
alpha/visibility bookkeeping is right and the last-frame leak is not our state. After three rounds where a
confident read of this code produced a wrong fix, the next animation change comes from these numbers, not
from me looking at the file again.

Verdicts the b1364 round won: quick actions accepted ("like and reply working, quick action working"); the animation itself rejected ("expanding, masking lag rha hai, wapas collapse … ek jhatke se"). The tile fix that earned the first half is below, in the b1362/b1364 line of work: it was `show a tick, postDelayed 500 ms, THEN send the PendingIntent and collapse the card`, firing 503-537 ms after **every** tap, which is the whole of "action register nahi ho rahe, tick tick lage jaa rahe hai". It now sends at once, marks at once, and leaves the card open (dismissal follows the notification's own removal).

Before that, the **b1362** round (release `ci-362`, commit `f26a662`). It is the
first round decided by numbers instead of by whoever read the code last: the tester's own export showed eleven
user morphs at `avg=15ms slow=0` - i.e. **at the panel's 60 Hz ceiling, dropping nothing** - while his report
said the whole island felt sluggish, and it also named the two things that actually cost: a notification flood
that ran 44 morph setups in 6.7 s to produce **33 morphs with zero frames** (~2 s of main thread for work
nobody could see), and hitches that all sat at `t=1.0x`, one frame after an animation, growing from 17 ms to
66-124 ms as the ring filled from 0 to 8 chats. So: the window now *votes* for the panel's own maximum rate
(`preferredRefreshRate`, verified against AOSP rather than memory, with `[DISPLAY] modes=… vote=…` logging
what the panel offers), the frame budget is measured rather than hardcoded (a 16 ms "slow" threshold is blind
at 120 Hz, where 16 ms is two dropped frames), `core/FrameWatch.kt` counts every vsync plus every layout pass
plus the card's own draw time (`[FRAME] frames= hz= gap= slow= layouts= draw= worst= window=`), a ping
arriving mid-flood re-pops in place instead of rebuilding the morph, and `updateIslandLayout` returns early
when size and radius are unchanged - which is exactly the end-of-morph case, since `beginMorphPerf` already
applied the final size once. Nothing in the drawn box, the clip or the interpolators was touched. The trace
screen gained EXPORT TO DOWNLOADS (`hip-log-<stamp>.txt`, MediaStore, unique per tap, app-folder fallback),
the buffer grew to 1500 lines / 600 persisted because the old size lost the evidence that would have answered
"which build felt slow", and `[BOOT]` prints the installed `versionName` so a report can name its build.

Before that, `main` head is **b1359** (release `ci-359`, commit `1dd02b3`) = the **b1353 code, restored**: `git diff
c22dc55 HEAD -- app/` is empty. The b1356 attempt at the content-jitter fix was **reverted** on 2026-09-21 on
the tester's report that it made the whole island slower than b1353 ("pura island he laghu ho gya"), including
its three coupled changes: the shift moved onto the card view, b1350's content pin deleted, and the deferred
region pass re-owned. Lesson written where it will be read: after a regression the next build may add
*measurement only*, and never a fix bundled with the revert. The text jitter from b1353 is therefore still
open, with a clean baseline; the suspects to measure (not assume) are the hardware layer on the text column
(b1350), `clipToOutline=false` + a non-rectangular `canvas.clipPath` per frame (b1353 - on a hardware canvas a
clipPath can push the subtree into an offscreen re-raster every frame, which is exactly an "everything got
slower" signature), and the content pin (b1350).
The code below is described as of b1353:
`last-good` still points at `ff98959` (release `ci-314`) on purpose: the morph feel, the swipe feel and the
sender-name fix are all waiting for an on-device verdict from the only tester we have. When a build is
called good, move `last-good` to it.

b1353 = the morph does **no layout per frame at all**. The tester proposed the architecture ("pura screen pe
overlay karo, jitne pe island draw hoga bas utne ke touch ko island ko denge, baaki peeche bhej denge"); half
of it already exists (the overlay window is MATCH_PARENT full-screen and the touchable region is shrunk to the
island rect - via the hidden `touchableRegion` reflection listener, not via `FLAG_NOT_TOUCH_MODAL`, which the
flag mask does not contain). What was left, and what this round took, is the cost side: the card view used to
be *resized* every frame. Now `beginMorphPerf` lays it out once at the target size and the card view draws the
animated box itself (`MorphFrameHost` / `onDraw` + a containment clip on the fading subtree + a `translationY`
that keeps content centered in what you see). `core/IslandMorphFrame.kt` owns the anchor maths with 7 tests -
x centered, y hanging from the island's own top, because that asymmetry is what the window placement does and a
1 px error per frame is the jitter we are trying to delete. `endMorphPerf` hands background/clipToOutline back.
Step 3 (the other half of his idea - a full-screen `FLAG_NOT_TOUCHABLE` visual window plus an island-sized
touch window with `NOT_TOUCH_MODAL|WATCH_OUTSIDE_TOUCH`, deleting the reflection hack) is deliberately not in
this build: it moves reply focus and the ghost-reply morph's coordinates, so it needs its own round and his
verdict on this one first.

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
against real capture values (65 tests in CI total: 18 display + 15 identity + 14 gesture + 5 trace log + 6 morph jank + 7 morph frame), and the rule is: **a group is named by which thread, a
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
(`lastDebugMessage` is already written and never shown); **P0-3 = the two-window split** (visual window
full-screen with `FLAG_NOT_TOUCHABLE`, touch window island-sized with `FLAG_NOT_TOUCH_MODAL +
FLAG_WATCH_OUTSIDE_TOUCH`), which deletes the reflection inset hack *and* the real hazard found on 2026-09-21:
if that reflection fails, the catch falls back to a full-screen **touch-modal** window, i.e. the phone stops
responding to touches while the island is up - and adding `NOT_TOUCH_MODAL` alone cannot fix it, since nothing
is "outside" a MATCH_PARENT frame; P0-4 drop
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
