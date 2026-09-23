# The next mechanism: the morph follows the finger, and keeps its velocity

This is not a knob. It is the one production behaviour the app still does not have, and he asked for it directly
and early: **"premium UX = gesture follows the finger; animation only at ACTION_UP is wrong"**. Apple's island and
Xiaomi's both work that way: the shape is *under the gesture* while the finger is down, and when it is released
the animation continues **from the finger's velocity** instead of restarting from a duration.

## Why the architecture can take it now (it could not in b1350)

| Needed | Where it is |
|---|---|
| A box at an arbitrary size, drawn rather than laid out | `IslandMorphFrame.compute` + the host's `applyMorphFrame` — one `invalidate` inside the view, no window resize |
| The content's scale/alpha/entry as a function of *the shape*, not of a clock | `MorphCarry.shapeProgress` → `openProgress` → `contentScale` / `contentAlpha` / `contentEntryOffset` |
| One owner per property per frame, so a drag cannot fight an animator | `setMorphContentAlpha` (both styles), the rider's own `translationX/Y`, the host's `contentOffsetY` |
| A measured gesture pipeline to take the velocity from | the ring drag: `startRingPush` / `endRingSwap` / `clearDragVisuals`, `VelocityTracker`, `frameWatch` |
| Regions for touch, so a drag can start on the pill | `forceRegionUpdate` + the a11y touch regions |

The morph was previously "start a 380 ms ValueAnimator on ACTION_UP". Everything that made that the only option —
the per-frame `layoutParams` write, the whole-view fade, the row's opacity owning the icon — has been removed over
six rounds, mostly because he kept rejecting the symptoms it produced.

## Design

1. **Press**: `ACTION_DOWN` on the pill opens a *gesture morph session*: `morphPinW/H` fixed at the larger of the
   two ends (already how it works), `morphAnimator` not started, `beginMorphPerf("gesture->…")` so the same
   measurement runs.
2. **Drag**: `translationY` of the finger maps through `IslandDragMap` (new, pure) to a target box size:
   `w = lerpEven(pillW, cardW, d)`, `h = lerpEven(pillH, cardH, d)`, `r` likewise, `d` clamped, and a **rubber
   band** past the ends (`d` beyond 0/1 is compressed 0.25) so it is soft but never shows a box outside its two
   real sizes. `d` also drives everything else, because the content already reads `open` from the drawn box:
   no new content code, and the icon ride works while dragging (its travel is the box edge).
3. **Release**: one decision, no re-animation of what is already true. Take `v = velocityY` and hand the
   *remaining* distance to a spring: `SpringAnimation` on `d` with `stiffness` chosen by direction and initial
   velocity `v / distancePerDp` — so a fast flick covers the last 20% in a few frames and a slow release eases in,
   and in both cases frame 1 of the animation continues frame N of the drag (that continuity is the whole of
   "no jhatka"). A release with `|v|` under the threshold snaps to the *nearer* end rather than the *intended*
   one, which is what iOS does for a partial drag.
4. **Interrupt**: a new DOWN while the spring runs cancels the spring and resumes the session at the current
   `d` — that is Apple's interruptible transition, and it is free here because `d` is the only state.
5. **Cancel**: a drag that goes back under the touch slop with no velocity returns to `d = 0` with the same
   spring, and `endMorphPerf` reports `gesture=drag frames=… released-at d=…`.

## What it fixes that no slider can

- "texts kabhi-kabhi upar-neeche hilte hai fir set hote hai": a re-layout at the *end* of an animator is what
  makes the text land. If the last 20% was already drawn at the finger's sizes, the settle has nothing to change.
  This is the same argument that killed `setContentPinnedForMorph`'s opposite, and it is why the frame lock stays.
- The last-frame ghost class: the settle is where `clearMorphFrame` hands the background back; with the shape
  already at the target when the finger lifts, the handover lands on an identical frame.
- It makes the speed question moot: there is no duration to argue about, only the finger and one spring.

## Risks I will not hide

- 120 Hz drag means `d` changes every frame, and every frame is an `invalidate` inside a screen-sized overlay: it
  is the cheapest path we have, but it is the path the frame meter exists to check, and I will ship it only with
  `frames/slow/regions` numbers from his own log to prove it did not cost frames.
- The a11y touch region must not swallow the ring swipe: the pill's vertical drag opens the island, the
  horizontal one changes the notification — the axis decides, and the axis test belongs in `IslandGestureTest`.
- `SpringAnimation` needs `androidx.dynamicanimation`; if the dependency is not in the build it goes in, and that
  is a Gradle change, so CI is the gate.
