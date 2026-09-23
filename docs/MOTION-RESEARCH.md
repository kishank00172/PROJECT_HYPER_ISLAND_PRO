# Motion research behind the morph — what each rule copies, and from where

Kept in the repo because the answer to "koi research he nahi" should be a file, not a message. Every row names
the symptom in his words, the source that speaks to it, what was copied from it, and what it looks like in our
code. Where a source is a third-party transcription rather than the primary document, that is said out loud —
and where something is my inference, it is labelled, never presented as a diagnosis.

## 0. The primary source is his own dump

Everything below is downstream of measurements already in the repo, because taste claims can be argued with and
numbers cannot:

| Observation | Where it came from |
|---|---|
| The island hangs from its own Y: the box's top is always 0, it grows **downward**; it stays horizontally centred, `left = (boundW - boxW)/2` | `IslandMorphFrame` doc + `updateOutlineForIsland` arithmetic |
| While a morph runs, the host translates **every** child by `frame.contentOffsetY` so the row stays centred inside the drawn box → for a 104 px pill in a 260 px card that is ~78 px of downward drift | `applyMorphFrame`, `MorphFrame.contentOffsetY` |
| Content is clipped to the drawn box during a morph (`clipPath` in `dispatchDraw`), so nothing of the card can paint outside the island | host `dispatchDraw`, comment "containment clip, not a mask that reveals" |
| `shapeProgress` runs 0 → 1 in **both** directions | `MorphCarry.shapeProgress` |
| 120 fps means 120 *smaller* frames; `frames=20 avg=16ms max=43ms@t=0.79` on a collapse | his exports, `[MORPH] end` lines |

His description of the old feel, verbatim: *"pill center mei hai and niche hoke dono side (left, right) uniformly
expand hota hai, but uska content ye feel nahi deta — aisa lagta hai upper right side se niche center ki aur aa
rha hai and 3rd option upper left side"*. That is the two rows above read together: a horizontal entry from the
carry (from the right) plus a vertical entry that is the box's centring arithmetic, and with the carry off, only
my horizontal slider's offset — pointing the other way. The arithmetic was in the code the whole time; the
diagnosis is mine.

## 1. Apple — Dynamic Island content

Source: Apple's own transition list as transcribed at stackoverflow.com/questions/74377313 (an answer quoting
the developer docs, not the docs themselves — treat as a good secondary), plus `matchedGeometryEffect`
descriptions at hackingwithswift.com and blog.jacobstechtavern.com.

| What the source says | What we copied | Where it is |
|---|---|---|
| Inside the island, content animates with **`opacity`, `move(edge:)`, `slide`, `push(from:)`** and `numericText`. There is no "scale the content" in that list. | The content's entry is an **offset on the growth axis**, not a box scale. The scale stays a small secondary (12 % default) and is now a switch, not the mechanism. | `MorphCarry.contentEntryOffset`, `contentScale` |
| The island is top-anchored: elements emerge from under the notch and are clipped by the island's own bounds. | The row **hangs from the box's top edge** — where the pill is — and settles into its centred rest place as the box completes. `-(centreLeftover + dropPx) * (1 - open)`: proportional to the leftover it has to cover, so it lands on exactly 0 and there is nothing to snap. | `applyMorphCarry`, `MorphCarryTest` |
| `matchedGeometryEffect` captures the geometry of both views and interpolates **one** internal intermediary, so the element keeps its identity across the states. The documented failure mode of doing it wrong is two copies drawn at once. | We have two real views, so the exclusion is stated: while the riding icon's row is being drawn, the pill's own icon is **not drawn at all**. `pillIconHidden(rowAlpha, rowShown)`, and the reader takes the row's *actual* drawn alpha, whichever owner wrote it. | `MorphCarry.pillIconHidden`, `applyMorphCarry` |
| `contentTransition(.numericText())` — digits roll when a number changes. | **Not implemented.** Filed as the next idea for the badge count, since our badge is the one number that changes mid-transition. | — |

## 2. Material 3 (Google) — container transform, shared axis, expressive springs

Source: m3.material.io (motion, container transform, shape morphing) as summarised at `ux.detroit3d.com`'s
Material 3 motion notes and the Android M3 expressive component notes — secondary summaries of the spec, so the
*patterns* below are theirs and every *number* is ours.

| What the source says | What we copied | Where it is |
|---|---|---|
| **Container transform**: a small element grows into a larger container; the content inside **fades and slides**, entering with the container rather than after it. | Style "Balanced" does exactly this on our shape clock; the entry offset is now on the same axis the container grows on. | `applyMorphCarry` |
| Enter: **fade + scale from 80 %**; exit: fade + scale **to** 80 %, with **exits faster than enters** ("keep the momentum"). | The content's exit deadline is one slider — `goneBy` (25–90 %, default 45 %) — replacing my two hand-measured constants (0.40 to idle, 0.45 to the ping pill). Past it, the row is at exactly 0, and the sweep test fails if anything is drawn over a closed pill. | `contentAlpha`, `AppSettings.getMorphGoneByPct` |
| **Shared axis**: consecutive views share continuity by moving along the axis they share. Our shared axis is Y (the pill sits above the card's content area). | The content moves only on Y during the morph. Sideways travel of the row was removed — that was the side bias he named. | `applyMorphCarry` (no `translationX` for the row when `entry=drop`) |
| **Staged / staggered** children with the last delay well under the duration, so nothing waits on a tail. | `childOpen(index, count, open, stagger)`: header → title → message → actions, each with an equal-length window inside the same travel, all finishing with the shape; on the way out the order reverses so it folds back from the far end. | `MorphCarry.childOpen`, `applyMorphCarry` |
| Shape morphing and springs use the **expressive** scheme (low damping, visible overshoot); utilitarian motion uses **standard**. | Deliberately **not** copied: an overshooting spring on a box that is drawn inside a clamped frame eats its own bounce (measured on hardware earlier: 29 of 46 frames moved the box by 0.0 %). Kept available only if he asks for bounce. | `morphInterpolator` comment |

## 3. Rejected — and why, so nobody re-tries it

- **Scaling or resizing the window/box per frame.** A screen-sized overlay window laid out 60× a second is what
  caused the earlier frame drops; the box is drawn, not resized (`IslandMorphFrame` exists for this).
- **A moving clip edge as the animation** ("mask reveal") — rejected on device twice. The containment clip stays,
  the reveal does not: motion now comes from the content's own offset, scale and opacity.
- **Cross-fading two copies of one element** — the exact bug class `matchedGeometryEffect` exists to prevent, and
  the cause of "icon duplicate … pill bhi duplicate".
- **Keying the content policy on "is the box growing"** — a pill pop from idle also grows; the axis that matters
  is *what the morph is heading for*, decided once in `openProgress`.

## 4. What an app-side instrument still cannot see

The trace log measures our own frame loop, layouts, GC and the drawn box. It cannot see the RenderThread's queue,
the SurfaceFlinger composition, MIUI's own overlay handling, or what the panel did between vsyncs. So "every
number is clean and it still looks wrong" remains possible — that is the space his device verdicts live in,
which is why the entry direction, the stagger, the drop distance, the exit deadline, the ride and the style are
all sliders and radios in TestLab instead of decisions of mine.
