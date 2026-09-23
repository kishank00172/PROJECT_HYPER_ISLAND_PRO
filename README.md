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

## Current position - updated 2026-09-23 (read me first if you lost the plot)

**b1411** (release `ci-411`, code `b4503c4`, 143 JVM tests green, APK 2 870 374 B, sha256
`62e1b5aed56d426472795aa3aea0654716243a4b34af3c55f79f5c6a2f15ec71`) is the build to judge the two new looks on:
it is b1406 with the three defects his own trace exposed, all of them mine, plus the shade probe off the main
thread. `docs/AI-MOTION-SHOWDOWN.md` §0.5 has the numbers.

Frame-by-frame tables for both new looks, with each design's own wording next to the function that implements it and the pixels his device actually gets (366 x 104 pill, 1067 x 421 card, 60 Hz): **`docs/THE-TWO-MOTIONS.md`**. That doc is also where the two defects it exposed are written down - a spring sized against 720 ms on a 360 ms animator, and phases measured on a quantity that front-loads.
**What his log said, against what I had claimed.** Frames were fine (median `avg=15ms`, `frames=24` on the new
styles, identical to the accepted ones), so "glitchy" was geometry: (1) `cutout=540/40px` on a 1080 px screen with
the island centred at 540 - Claude's rule as written demanded a 224 px shove there, the collapse obeyed, and the
layout yanked it back one frame later: that is his "pill kabhi right shift ho ja rha hai". A lens the island is
sitting on cannot be dodged sideways, so the rule now refuses when the hole is contained, caps what it will move,
ramps to zero at both ends (`avoidRamp`) and is not applied to collapses at all - inert on his device, still real
on an off-centre one. (2) The spring's headroom widened the *view*, and the row's left edge, the pill's and the
row's measured width are all anchored to the view: ~10 px of offset that snapped back at the settle, plus a text
re-measure in the last frame - b1343's "text set hota hai" snap re-imported by me. `setContentPinnedForMorph` now
pins the row's width as it always pinned its height, and the two X anchors subtract `viewExcessHalf(pin, final)`,
which is 0 for the four classic styles (a test says so, and the same test caught that my first version of the pin
would have re-measured every *collapse* at the pill width). (3) The "dono ek he hai" one: `response=0.42s` inside
a 380 ms window never reaches its target before the interpolator pins the last frame to 1.0, so the bounce, the
settle and the whole reason to run a spring were truncated into a mild ease - and a 3 % squeeze over 40 ms is two
frames on a panel his log shows at `hz=60`. The response table is now bounded by the window
(`everySpringFitsInsideTheWindowItIsGiven` fails the build otherwise), phase A is 0.22 of the morph at 5 %, and
the presets carry 1 px / 21 px / 68 px of overshoot on his card width instead of three decimals. Two smaller ones
from reading my own patch: the ripple ran on collapses too at full glass strength (a fog flash on every return),
and the capsule had the gate without the travel, which made "the shape leads, the content follows" a fade timing
rather than a movement - it rides now.
**And the part that was never about the new styles:** 263 `[STALL]` lines, 136 with our frame on the stack, 80 of
them `checkNotificationShadeState` at ~47 ms each - 3 750 ms of main-thread blocking in ten minutes, landing
inside 33 of his 74 morph windows across *every* style. b1396's answer was a throttle, which only limits how often
we block; the probe now runs on its own daemon thread with a single-flight guard and posts its verdict back, and it
measures into a local Rect instead of `outlineRect`, the shared one the outline provider reads while drawing. A pill
that hides ~40 ms later is the cheaper trade. His next trace should show `checkNotificationShadeState` gone from
the `[STALL]` lines - if it does not, this change did not work and I will hear it in his numbers.


**b1406** (the build he judged, release `ci-406`, code `361eef7`, 138 JVM tests green, APK 2 866 170 B, sha256
`76c72e8cf2d94ec86e5b65a57b933a028f980a8a111467801173ccbc66280e1d`): the two outside designs he benchmarked this
app against are now **two selectable looks**, side by side in the style list, plus the fix that explains his last
verdict. Full claim-by-claim mapping: **`docs/AI-MOTION-SHOWDOWN.md`**.

Four pushes stood between the plan and that APK, and every failure was mine rather than CI's: an unescaped `"`
inside a `android:text="…"` attribute (aapt died before Kotlin even ran), then `android.view.animation
.TimeInterpolator` when the type lives in `android.animation`, then a guessed `DisplayCutout.getBoundingBoxes()`
where the SDK says `getBoundingRects()`. The house rule that the compiler is the cheap reviewer held again - and
this round it caught them in 30 seconds instead of costing a build-and-flash cycle. This paragraph arrived after
the APK was built, so the release tagged from *this* commit carries the same code; the file to install is `ci-406`.

**First, the bug his complaint was actually about.** "4th mei kuchh to alag hai he nahi, 3rd jaisa he to hai"
was not a tuning miss: `AppSettings` clamped `KEY_MORPH_STYLE` to 0..2 in *both* the getter and the setter, so
selecting Glass settle (3) stored 2 and read back 2 - the glass branch, the `RenderEffect` blur and the radio's
own effect had never run, on any build, since the style was added. He was describing the app correctly. Range math
is now one number (`MotionVariant.MAX_STYLE`) used by both accessors through one function, and three JVM tests
fail the build if a style escapes the clamp, shares a name, or if `MAX_STYLE` stops covering the last constant.
He still owes Glass settle one re-judgement, now that it can be chosen at all.

**Style 4 - Liquid capsule (Claude's design),** and **style 5 - HyperMorph (ChatGPT's design)**, both driven from
one new Android-free file, `core/MotionVariant.kt`: a spring as a closed-form curve (his `response`/`damping`
API, not mass/stiffness), a content gate until ~60 % of the target width, `cornerRadius = height/2` while the box
moves, phase-A compression at 97 % x 106 %, a micro-settle that is the spring's own tail rather than a second
animation, staged pill return (content gone by 65 %, container after), `magnetic(t, pull)` for content pulled
into the pill, and a `sin()` energy ripple spent as one quantised blur + a 1.2 % breath of size. Per-trigger
`snappy / silky / bouncy` presets, four sliders with their authors' numbers as defaults, and a **COMPARE A to B**
button that replays both morphs back to back in one tap and leaves the picker on the last one. The `[MORPH]`
start line now prints `variant= profile= response= damping= overshoot= gate= magnet= squeeze= cutout=` so a
verdict and a log can be checked against each other - the thing the clamp bug made impossible.

**Refused, in writing, rather than faked:** `SpringAnimation` (a dependency whose snippet lays the window out per
frame - the exact b1343 regression), metaball merge of simultaneous islands (needs two surfaces; there is one),
and Firebase/Remote-Config delivery + Compose shared-bounds (no backend, no Compose in this app). A third model's
reply becomes style 6: one constant, one name in `MotionVariant.styleName`, one radio. 136 JVM tests (19 new).

The previous round was the **b1401** build (release `ci-401`, code `6dc0ed4`, 117 JVM tests green; APK 2 843 138 B,
sha256 `df4d889dd1bf25c980c1e22285569cae3cf965039d962d7be2948fa8a9142ae8`). Two instructions, both about the same
mistake seen from two sides - "4th mei kya hai, kuchh to alag hai he nahi, 3rd jaisa he to hai" and "baki ke jo do
the unka icon morph pichhle do-teen build se kharab kar diya ... jo ek maine praise kiya tha usko recover karo".

**The icon ride is recovered from `5d54874` (b1378),** the only animation change he has ever praised. Mechanism for
mechanism: the glyph scales from the pill's relative size to the launcher's about its own centre on the clock,
swaps its drawable once at the midpoint, and travels the drawn box's own left edge *undivided*, so its travel ends
at zero where the row rests. Five of my own later changes were what cost it, and each one was a correctness win
that spent the property he was actually praising: the row's sideways entry was replaced by a vertical drop (the
icon's travel was a side effect of it, so the icon lost it too); the travel and the size were divided by the row's
scale so they would land exact; the two icon slots' distance was measured and added back as a vertical drift
(`riderY=-150px` in his log - 150 px of drift inside a 260 ms morph, in a layout whose two rows share one line);
the pill's own copy stood down for the ride's whole duration, so the badge appeared mid-flight; and the row's fade
moved onto its children, so every view owned an alpha again. `riderShift`, `riderScale`, `pillIconVisible`,
`riderSlotDeltaY` and `setMorphContentAlpha`'s per-child branch are deleted, not tuned. `docs/MOTION-RESEARCH.md`
§4 is the table of what each trade bought and cost, with the rule that follows: **a shared element that is part of
a moving container is not driven separately from it** - and when the numbers say a motion is exact while he says it
looks wrong, the numbers are what to question. The ride's Lab checkbox is deleted too, not defaulted: his capture
had `ride=off` on every morph of the session, so one unticked box had quietly removed the animation he likes from
all four styles. The price is in the code so nobody re-fixes it: in the styles that scale the row, the icon's
travel is scaled with it (12 % short early on, exact where it lands). That is what riding means.

**The fourth style is now a look, not a timing difference.** Per-part flight is withdrawn - a real mechanism,
tested, and invisible to him. **"Glass settle"** takes its place, copied from the material iOS 26 is built on:
Apple's glass responds to what is under it and morphs between states, and its lensing is precisely what Reduce
Motion exists to strip out, so the blur *is* the motion. The card's text arrives legible but out of focus and
sharpens as the box completes; collapsing, it blurs back out instead of only dimming. Android has had the same
thing as a render-node property since API 12 (`RenderEffect.createBlurEffect`), so the cost sits on the render
thread and not in our frame loop: one effect shared by the four content views (never the row, so the icon stays
sharp), quantised to half-pixel steps, cleared entirely below 0.5 px so a settled card carries no effect at all.
The style keeps the shape-driven scale, which is what stops it reading as "scale + fade" from across the room.
`[MORPH] start` prints `ride=classic(b1378)` and `glass=on blur=18px`, so a verdict and a log agree on what he
felt, and `riderY=` is gone with the thing it described.

Before that: the **b1399** round (release `ci-399`, code `793b331`..`d246e3d`, 118 JVM tests green; APK
2 845 414 B, sha256 `b0856bf75f17928cff77b52397354ee1dc47dc4b36126e144c123b6cdd612d05`), his log-driven bug set. He did not judge the ride
and the roll; he answered with two demands and three bugs, and all four are visible in his own capture
(`hip-log-20260923-163536.txt`, 574 lines), which is now written up in `docs/ISLAND-LIFECYCLE.md`:

- **"Abhi sirf 3 hai … research karke kuchh esme naya banao"** - true, and worth admitting plainly: the previous
  round added a mechanism and five sliders, but the *style list* still had three entries. So the Lab has a fourth
  form, **per-part flight** (`AppSettings.MORPH_STYLE_PER_PART`), copied from Apple's per-view content
  transitions (`move(edge:)`, `push(from:)`, plain `opacity` - each element along one edge) and from the way M3's
  container transform stages its inner views: the title pushes in on the pill's edge, the message fades through
  with a short rise, the action tiles rise from the bottom edge last, and the row itself is neither faded nor
  scaled - mixing a whole-row fade with per-element windows hides which element is doing what, which is the whole
  point of the form. `MorphCarry.contentAlpha` became `contentOpen`: one number (exit deadline folded in) that
  both the opacity and every part window read, so a collapse plays the windows backwards for free - the buttons
  leave first, the title is the last thing standing.
- **"quick action use kiya … island ko wapas pill banna chahiye tha, wo nahi hua"** - the path had no closing
  trigger and no instrumentation (zero `[ACTION]` lines in 574). Two faults: an earlier round removed the hide
  timer and never replaced the event it was waiting for, and the cancel that *did* arrive missed, because
  removals carry the status-bar key while pages are filed under the conversation key
  (`dismiss: no page for key=com.instagram.android|thread|kish.ank001 ring=1`). Now: the acting app's window coming
  forward collapses the island (`STAGE2_PING`, or idle on an empty ring) with a 4 s bound for actions that open no
  window - standing down if the card was already closed or moved to another page - the removal falls back to the
  key stored with the page, and every step logs. Pages are still never deleted by us.
- **His own reply came back at him as a card** (`send tapped text=hiii` -> 4.9 s later `new page 'You'`,
  `COUNT 1->2`, `[MORPH] start notify->ping`): `isMatch` rejected the entry on `MIN_ECHO_TEXT` before any rule
  could look at it, so the four-character reply never reached the two tiers that could catch it. A self marker
  (title exactly `You`/`Me`) now suppresses at any length - it is the app announcing the sender, not a claim about
  my sentence - whole-text equality is still required, the length floor stays for the text-only tiers, the
  Instagram window went 2.5 s -> 6 s because 4.9 s is what the phone did, and every suppression writes
  `[ECHO] dropped own reply ...` into the trace.
- **The log itself** - he could not read it, and the noise had a source: my round-21 shelf probe, doing
  `getWindows()` + `getRoot()` (binder, main thread) for every window change on the phone: 220 of 574 lines,
  24-37 ms each, one of them at `t=0.76` *inside* the collapse it was measuring. It now runs only for events that
  could be the shelf, at most twice a second, nested so the ingestion fallback still runs. The viewer no longer
  fights a finger: the tail moves only while it is at the bottom, nothing touches the scroll between
  `ACTION_DOWN` and `ACTION_UP` (the old swap + restored offset + `post`-delayed `fullScroll` is exactly the
  "kabhi upar kabhi niche" teleport), identical consecutive lines read as `... (x9)`, and `[STALL]`/`[FRAME]`
  hide behind `QUIET` - display only; the buffer and the export still carry every line.

Answered in the same round, because it was asked: the trace is not a recorder that starts on a change. The
service writes a line as it decides things, all the time it is alive; the only periodic lines are the sampler's,
and those are now hidden by default. `docs/ISLAND-LIFECYCLE.md` is the reasoning for this round;
`docs/MOTION-RESEARCH.md` gained the sources for the fourth form (and the `numericText` row is no longer a wish).

Before that: **b1396** round (release `ci-396`, code `befef0e`..`e04df63`, 107 JVM tests green; APK
2 834 810 B, sha256 `22fb68200ec6da89f377b56e867e0b348d98f73530e65275049729341b46eae4`). He noticed that the icon
transformation had stopped being seamless - "pehle ekdum seamlessly tha ... abhi notice ho ja rha hai change" - and
he was right for a structural reason: the ride had never owned anything. Its travel was a side effect of the row's
`translationX` (so the round-23 drop deleted it) and its opacity was the row's fade (so the ride ended at his
`goneBy` slider's 25-45 % while the pill's icon popped in behind it). A shared element now owns its travel (the
box's left edge plus the measured distance between the two icon slots) and is never faded by the container it is
leaving: while a ride runs, the fade is written on the row's content views by a single function both opacity owners
call, the stagger rides along in the same pass, and the parent's scale is divided back out of the shift and the
size. New mechanism from Apple's list, not a knob: the badge count rolls the way the number moved
(`PillBadge.rollDirection`, `contentTransition(.numericText())`), with a TestLab switch, and the morph's start line
now prints `riderY=..px` so the travel is provable from his log instead of assumed. The next item is designed, not
tuned: `docs/MORPH-GESTURE-PLAN.md` - the morph under the finger, released into a spring that keeps the finger's
velocity, interruptible mid-drag.

Before that: **b1392** round (release `ci-392`, code `3245434`, 105 JVM tests green; APK 2 831 422 B,
sha256 `6e1f300510352e53aa9c5654f036ac8f4f46c8742cbfdcfe0d8f0028d56bcb5d`). He said the shape-driven styles "don't
feel like the content came down from the top", because the pill is centred and the box grows evenly on both sides -
while the content slid in from the upper right (or upper left without the carry). Two offsets in the code explain
it exactly, and both were mine: the carry pinned the row's left edge to the box's left edge (a sideways entry), and
the host re-centred the row in the drawn box every frame (78 px of downward drift that belongs to the box). The
horizontal lead slider of the previous round invented a third axis and is deleted. Content now hangs from the box's
top edge and settles onto its rest position on the same multiple of the leftover it has to cover, so it lands on
0 - pivot follows the anchor. New TestLab controls, all of them his to move: entry (drop vs centred), drop
distance 0-40 dp, staggered entry 0-60 % (header, title, message, actions), and an exit deadline 25-90 % that
replaces the two constants I had measured by hand. `MorphCarry.openProgress` is the single place a morph's
direction exists, because three consumers re-deriving it is what shipped the collapse leak in b1386. The Morph Lab
moved to the top of TestLab ("new options dikh nahi rahe" - it was the sixth section). `docs/MOTION-RESEARCH.md`
records which rule came from which source, including what was read and deliberately refused (M3's expressive
spring, eaten by our box clamp).

Before that: **b1390** round (release `ci-390`, code commits `0c2cf77`..`c446c1d`, 103 JVM tests
green; APK 2 824 966 B, sha256 `05da990484bf7a288caea706bb0178a0d279ca216b71c85100866aeccf6835ad`). He felt the
four Lab styles and reported that Balanced duplicates the pill - "icon duplicate hoke thoda right shift hoke
original wale pe draw ho jata hai", card content visible inside the collapsed pill for the last frames - and that
Scale+fade leaks the same content while being the right feeling, "upar se icon ride hoti to aur mast lagti";
Reveal: "faltu hai hatao". All three were true and the first two were one bug of mine: `MorphCarry.shapeProgress`
runs 0 to 1 in *both* directions (0 at the morph's start size), and `contentScale`/`contentAlpha` mirrored it as
if a collapse ran it 1 to 0 - so the card row reached full opacity exactly at pill size, its `bg_card` panels
drawing the "second pill" and its centred icon sitting right of the pill's own. Both curves now key on what the
morph is heading for (`towardCard`), not on its size delta, and `MorphCarryTest` asserts the visible ends plus a
sweep that fails if anything is drawn over the pill (the old green test had pinned the bug). From research, not
recombination: the icon ride is a switch of its own so it can sit on top of any style; while the rider is in
flight the pill's own icon is not drawn at all (`pillIconHidden`, Apple's `matchedGeometryEffect` never
cross-fades two copies); and a 0-48 dp "content pulled out of the pill by" slider moves the row in from the
shape's edge instead of scaling it, which is what Apple's `move(edge:)/slide/push` and Material's "fade + slide
from edge, exits faster" actually do. Reveal is deleted from the settings, the service, the Lab and the layout.

Before that: **b1386** round (release `ci-386`, code commits `09341ac`..`7315ccd`, 101 JVM tests
green; APK 2 820 422 B, sha256 `bc5f0a3a496130bafd01ad20dac54ba65ade4780c16cabe53692b578ca86b313`). He reported
that the count falls during a notification rain with nothing opened, and he was also right that the log had been
growing one line per symptom instead of covering the state. Both fixed: the count has one writer and it logs every
change with its cause (`4->11 chats=11 page=1/11 cause=new`), the ring wipe names its victims
(`CLEAR 12 pages ['VIJAY TRADER', ...] because=shade-open`), the shade decision logs the window that made it
(`shade -> OPEN (systemui window 47% of screen)`), and the badge rule now lives in `PillBadge` with a test on the
1-vs-2 boundary. The cause of his symptom was in the code, not the log: opening the shelf emptied the whole ring
and every notification arriving meanwhile was silently refused - `isShadeOpen` is a window-height test, so a tall
heads-up can trigger it with nobody touching anything. The policy is a TestLab choice now: **keep counting**
(default) or **shade wipes (old)**.

Before that, the **b1382** round (release `ci-382`, code commits `c8fec1e`..`854319c`, 100 JVM tests green; this README's own commit lands after the APK, docs-only).
The APK is 2 815 310 B, sha256 `a859e952385883f46ae17dba9062dc9e91f6840dc933779f3273ce03593658a3`.

**Handover rule, learned the hard way just now:** the `ci-<N>` tag is GitHub's `run_number`, which counts *every*
push including docs, so it is NOT the round number I keep in my head. I quoted `ci-379` to him for a build whose
code was still `924f64a` (b1378) - the sizes were 4 bytes apart and both looked plausible. Before a link goes
out: fetch the release, read `target_commitish`, and grep the downloaded APK for a string that only this round's
code contains (`Island Morph Lab` here) - a sha that round-trips proves the copy, not the content. Note that
`ci-379` (2 798 418 B) and `ci-378` (2 798 422 B) were 4 bytes apart, so size alone never identifies a build.
The round began with the first visual change he has ever accepted - "wo icon morph jo tha kaafi mast hai, ekdum
badhiya feel deta hai" - and the same message named what was still missing: "Island ke andar jo content hota hai
use bhi morph karo scale and opacity morph". So the row now scales and fades on the same progress number that
carries it, and that number is derived from the **drawn box** (`MorphCarry.shapeProgress`, from `left` and the
pinned bound) rather than from the animation clock, so the content's size belongs to the shape instead of being
timed next to it. Pivot on the leading edge: it grows rightward and downward the way the box does, so the first
glyph stays where the eye already found it. One owner per property, still the rule - the per-stage fade branches
and the fluid expand's own `gridRoot.alpha = t` are shut while the carry drives opacity, and `outBy` became a
parameter of `beginMorphPerf` (0.45 ping / 0.40 idle) instead of being duplicated in three branches.

Feel is now his to choose, not mine to guess, because he asked: "kya koi aur animation idea hai? Testlab mei he
daalna options choose karne ke liye". TestLab has a **Morph Lab**: four styles (balanced = ride + scale + fade;
ride only = exactly b1378; scale + fade only; and the old reveal kept as an A/B control), two sliders (how small
the content starts, where the travelling icon puts on the launcher badge) and a REPLAY button that plays
open-then-close on one press, so "thoda aur" costs a drag and a tap instead of an APK and an evening. The
service reads the settings at the start of each morph, and `[MORPH] start` prints the style it used, so his
verdict and my log agree on what he felt.

Two more of his six items were about the instrument, and both were my bugs. "notification counting badh ghat
kaise raha hai ... telegram wapas le rha hai kya" - answered from his own export: 12 ups and 7 downs, and every
down had a dismiss line beside it (Telegram cancels on open, we drop the page correctly); what was missing was
the *explanation*, which lived only in logcat. Now every ring mutation goes through `ringEvent()`, the badge
writes a `[COUNT]` line with `cause=`, and the removal code is carried through the grace-period closure so a drop
reads `because=he-swiped-it-away` / `app-cancelled-it-itself` / `opened-from-island`, while the bulk reasons that must
not cost a page log as `ignored removal`. And "kya sirf max 1500 lines hi trace karta hai ya export?" - yes, and
silently: his file held 1500 of 4186 lines. The buffer is 4000 with a `droppedLines` counter printed in the
export header, and the trace window itself got the fix his "log window glitch karta hai, kabhi starting pe kabhi
ending pe teleport" described: identical text is no longer handed to the TextView (204 of 464 stall samples in one
session were `render@TraceLogActivity.kt:236`), the scroll offset is captured and restored across a swap, only the
tail view follows, dragging up unfollows by itself, and `TraceLog` stopped constructing a `SimpleDateFormat` per
line.

`last-good` moved forward to `5d54874` (`ci-378`): the icon ride in it is the first change he has called good.
Still open from this round's measurement: the text-layout cost on main (`nComputeLineBreaks` 67 samples / 7232 ms
in his export) is now the biggest single owner of his frame drops, and it is not the morph.

Before that, the **b1378** round (release `ci-378`, commit `5d54874`, 96 JVM tests green), which is what
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
cached). `last-good` was still `ff98959` at that point; the text-jitter report stays open.

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
