# Motion research behind the morph — what each rule copies, and from where

Kept in the repo because the answer to "koi research he nahi" should be a file, not a message. Every row names
the symptom in his words, the source that speaks to it, what was copied from it, and what it looks like in our
code. Where a source is a third-party transcription rather than the primary document, that is said out loud —
and where something is my inference, it is labelled, never presented as a diagnosis.

> **Round 27 supersedes one claim in here.** The glass style was written up as shipped, and it *was*
> shipped - but `AppSettings` clamped the style id to 0..2 in both accessors, so it could never be selected and
> no verdict about it was ever a verdict about glass. That range bug, the two outside designs implemented as
> styles 4 and 5, and the refused parts of each, are in `docs/AI-MOTION-SHOWDOWN.md`.

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
| `contentTransition(.numericText())` — digits roll when a number changes. | Implemented a round later: the badge's digits roll in the direction the number moved, and the direction is derived once (`PillBadge.rollDirection`, +1 / -1 / 0) so the pill and the card cannot disagree. Switch in TestLab, `[MORPH] start` prints `roll=on/off`. | `PillBadge.rollDirection`, `HyperAccessibilityService.rollBadge` |
| Transitions are attached **per view**, and each one runs **along one edge** (`move(edge:)`, `push(from:)`, plain `opacity`) — the island's elements do not share one fade. | Tried as the fourth style (**per-part flight**, b1399) and **withdrawn on his word**: "4th mei kya hai, kuchh to alag hai he nahi, 3rd jaisa he to hai". Per-element windows are a timing difference, and a timing difference is not an option he can see. `partProgress` stays, because the glass form needs a readability ramp. | — (deleted in round 26) |
| The glass material of iOS 26 is defined by how it **bends what is under it**: it responds to what is beneath it in real time and morphs between states, and Apple's own accessibility switches (Reduce Motion, Reduce Transparency) exist to strip that lensing out — i.e. Apple treats the blur *as* the motion. | The fourth style is that material used as an entrance: **Glass settle**. The card's text arrives legible but **out of focus** and sharpens exactly as the box reaches its final size; collapsing, it blurs back out rather than only dimming. Android has had this as a render-node property since API 12 (`RenderEffect.createBlurEffect`), so the blur runs on the render thread and not in our frame loop. | `MORPH_STYLE_GLASS`, `applyMorphBlur`, `MorphCarry.blurRadiusPx` |
| Its performance caveat, from the same material's write-ups: do not animate too many glass elements at once. | Four views, one `RenderEffect` instance shared by them, the radius quantised to half-pixel steps so an effect is built per step and not per frame (a frame of this morph is 8 ms at 120 Hz), and anything under 0.5 px clears the effect so a settled card carries none at all. The icon is deliberately **not** glassed: it rides through sharp, because it is the element that carries the app's identity. | `applyMorphBlur`, `clearMorphCarry` |

## 2. Material 3 (Google) — container transform, shared axis, expressive springs

Source: m3.material.io (motion, container transform, shape morphing) as summarised at `ux.detroit3d.com`'s
Material 3 motion notes and the Android M3 expressive component notes — secondary summaries of the spec, so the
*patterns* below are theirs and every *number* is ours.

| What the source says | What we copied | Where it is |
|---|---|---|
| **Container transform**: a small element grows into a larger container; the content inside **fades and slides**, entering with the container rather than after it. | Style "Balanced" does exactly this on our shape clock; the entry offset is now on the same axis the container grows on. | `applyMorphCarry` |
| Enter: **fade + scale from 80 %**; exit: fade + scale **to** 80 %, with **exits faster than enters** ("keep the momentum"). | The content's exit deadline is one slider — `goneBy` (25–90 %, default 45 %) — replacing my two hand-measured constants (0.40 to idle, 0.45 to the ping pill). Past it, the row is at exactly 0, and the sweep test fails if anything is drawn over a closed pill. | `contentAlpha` → `contentOpen`, `AppSettings.getMorphGoneByPct` |
| **Shared axis**: consecutive views share continuity by moving along the axis they share. Our shared axis is Y (the pill sits above the card's content area). | The content moves only on Y during the morph. Sideways travel of the row was removed — that was the side bias he named. | `applyMorphCarry` (no `translationX` for the row when `entry=drop`) |
| **Staged / staggered** children with the last delay well under the duration, so nothing waits on a tail. | `childOpen(index, count, open, stagger)`: header → title → message → actions, each with an equal-length window inside the same travel, all finishing with the shape; on the way out the order reverses so it folds back from the far end. | `MorphCarry.childOpen`, `applyMorphCarry` |
| In a container transform the content is **inside** the container: it fades and scales *with* it, and the exit is faster than the entrance. | Kept as "Balanced". The two whole-row effects and the per-element windows are **not** mixed — with both on, he cannot tell which element is moving, and the log cannot either; per-part forces the stagger to 0 and leaves the row's alpha at 1. One `goneBy` deadline is shared by both, folded into the number the parts read. | `beginMorphPerf` (style read), `MorphCarry.contentOpen` |
| Shape morphing and springs use the **expressive** scheme (low damping, visible overshoot); utilitarian motion uses **standard**. | Deliberately **not** copied: an overshooting spring on a box that is drawn inside a clamped frame eats its own bounce (measured on hardware earlier: 29 of 46 frames moved the box by 0.0 %). Kept available only if he asks for bounce. | `morphInterpolator` comment |

## 3. Rejected — and why, so nobody re-tries it

- **Scaling or resizing the window/box per frame.** A screen-sized overlay window laid out 60× a second is what
  caused the earlier frame drops; the box is drawn, not resized (`IslandMorphFrame` exists for this).
- **A moving clip edge as the animation** ("mask reveal") — rejected on device twice. The containment clip stays,
  the reveal does not: motion now comes from the content's own offset, scale and opacity.
- **Cross-fading two copies of one element** — the exact bug class `matchedGeometryEffect` exists to prevent, and
  the cause of "icon duplicate … pill bhi duplicate".
- **A style that is a timing difference.** Per-part flight (b1399) gave each element its own window inside one
  progress number, correctly and with a passing test - and he could not tell it apart from "scale + fade".
  Ordering, easing and delay are parameters; colour, focus, size and silhouette are looks. New options are picked
  by that filter from now on.
- **A rider that owns its own path next to the container it belongs to.** See §5: it is the arithmetic that made
  the numbers right and the animation feel wrong, and it is why `riderShift` / `riderScale` / `pillIconVisible`
  are gone from this repo rather than tuned again.
- **A praised property behind a checkbox.** The ride's switch is deleted: his capture had `ride=off` on every
  morph of a whole session, so one unticked box had quietly removed the animation he likes from all four styles.
- **Keying the content policy on "is the box growing"** — a pill pop from idle also grows; the axis that matters
  is *what the morph is heading for*, decided once in `openProgress`.

## 4. The ride, broken and recovered — three "fixes" that cost the thing he praised

`5d54874` (b1378) is the only animation change he has ever called good: *"wo icon morph jo tha kaafi mast hai,
ekdum badhiya feel deta hai"*. Round 26 was spent getting it back, because he asked for exactly that: *"baki ke jo
do the unka icon morph pichhle do-teen build se kharab kar diya … jo ek maine praise kiya tha usko recover karo"*.
What makes this worth writing down is that **no number objected**: the frame meter, the jank sampler and the
morph log were all green while the ride was getting worse, because every one of them measured correctness of
composition and nothing measured unity of motion.

| What I "fixed" | What it made true | What it did to the eye |
|---|---|---|
| The content's entry became a vertical drop; the row stopped sliding sideways (b1392) | "content upar se aana chahiye" ✓ | the icon's travel had been a side effect of the row's `translationX`, so it lost its movement with it: a glyph resizing in place |
| The ride was given its own travel, with the row's scale divided back out (b1396) | travel lands exact at 0.88 scale instead of 42 px short ✓ | the icon stopped being *part of* a moving object and became a second object beside it. A 12 % distortion in one number is invisible; a separately driven element is not |
| The two icon slots' distance was measured once per morph so the hand-back aligned (`riderY`) | the rider ends on the pill's exact slot ✓ | −150 px of vertical drift inside a 260 ms morph, in a layout whose two rows sit on one line and need none. This is verbatim "notice ho ja rha hai change" |
| The pill's own copy stood down for the ride's whole duration (`pillIconVisible`) | one element, one copy on screen ✓ | the badge appeared mid-flight. In b1378 both rows moved with the box and the copies were never apart, so there was nothing to hand back |
| The row's fade was rewritten onto its children so a rider would not be faded by it (b1396) | the ride is not cut short at `goneBy` ✓ | every content view had its own alpha again - the same coupling class that had produced the duplicate icon and the pill leak two rounds earlier |

Recovered, mechanism for mechanism, from that commit: the glyph scales from the pill's relative size to the
launcher's about its own centre on the clock, swaps its drawable once at the midpoint, and travels the box's own
left edge *undivided* - which is zero where the row rests, so there is nothing to reset and nothing to snap. The
price is stated in the code so it is not "fixed" a fourth time: in the styles that scale the row, the icon's
travel is scaled with it (12 % short early on, exact where it lands). That is what riding means.

Rules this leaves behind:

* **A shared element that is part of a moving container is not driven separately from it.** Compensation
  (`÷ scale`, `+ measured delta`, `− the other copy`) buys exact arithmetic and spends the single property that
  made it read as one object. When the numbers say it is correct and he says it looks wrong, the numbers are the
  thing to question.
* **Recovery is a feature, and it is cheaper than another new thing.** Three builds of refinement were worth less
  to him than the one build he had already praised; "recover karo" is a legitimate instruction and it should be
  done by reading the old code, not by re-deriving what it probably meant. `git show <good commit>:<file>` is a
  first-class debugging tool for feel.
* **An option has to be a look.** Glass (focus), scale (size) and the ride (silhouette in motion) are all visible
  from across a room; windows, delays and easings are not.

## 5. What an app-side instrument still cannot see

The trace log measures our own frame loop, layouts, GC and the drawn box. It cannot see the RenderThread's queue,
the SurfaceFlinger composition, MIUI's own overlay handling, or what the panel did between vsyncs. So "every
number is clean and it still looks wrong" remains possible — that is the space his device verdicts live in,
which is why the entry direction, the stagger, the drop distance, the exit deadline, the ride and the style are
all sliders and radios in TestLab instead of decisions of mine.
