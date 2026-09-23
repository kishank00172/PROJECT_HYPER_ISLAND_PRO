# AI Motion Showdown — two outside designs, implemented side by side

Round 27. The tester pasted the two replies he had collected for the same research prompt — one from Claude,
one from ChatGPT — and said: **"dono models ka response hai, dono ka banao … main comparison karunga."**
Not my synthesis of the best ideas from both. Two designs, each as itself, selectable, so he can feel the
difference and pick. That is what this file documents: every claim in each reply, and what happened to it.

The three rules this round had to obey, from earlier rounds:
1. A new option has to be a new **look**, not a new parameter, and it has to live in the style list itself.
2. Nothing already praised may change. The four existing styles keep their curves, their fades and their
   defaults; every rule below is scoped to a style number.
3. Research must be shown, not claimed — hence the tables.

---

## 0. What was found before anything was written: the 4th style never existed

`AppSettings` clamped `KEY_MORPH_STYLE` twice, in the getter *and* the setter, to
`coerceIn(MORPH_STYLE_BALANCED, MORPH_STYLE_SHAPE_ONLY)` — 0..2. `MORPH_STYLE_GLASS = 3` was added later, so:

* selecting **Glass settle** in the Lab stored 3,
* reading it back gave **2** ("scale + fade only"),
* and the entire glass branch — the radio's effect, `morphGlassOn`, the `RenderEffect` blur, the settle curve —
  **never ran once**.

His round-26 verdict *"4th mei kuchh to alag hai he nahi, 3rd jaisa hi hai. Mere expectations pr khade nahi ho
paye"* was not an ungrateful reading of a subtle look. It was a correct bug report: the 4th option **was** the
3rd option. Two rounds of tuning a "distinctness" that was unreachable, on my side.

Fixed by deleting the literal range: one `MotionVariant.MAX_STYLE`, one `MotionVariant.clampStyle()` used by
both accessors, and `MotionVariantTest.everyDefinedStyleIsSelectable` / `noStyleNumberIsLeftOutOfRange` /
`everyStyleHasItsOwnName`, which fail the build if a style number can escape the clamp or share a name. A test
on the device could never have caught this — the app *looked* like it was honouring the radio. The bug is one
line of range arithmetic, so the guard is arithmetic too.

**He should re-judge Glass settle once**, now that it can actually be selected. That verdict is still owed.

---

## 1. Claude's design — "Dual-Spring Liquid Capsule" → style 4, `Liquid capsule (Claude)`

| His claim | Verdict | Where it lives | What he can feel |
|---|---|---|---|
| "do springs chalao. Ek shape ke liye, ek content-opacity ke liye jo thoda delay se trigger ho" | **implemented, differently** | `MotionVariant.spring()` drives the shape (via `morphCurve` on every morph animator); the content runs on `MotionVariant.gated()` | the box moves, *then* the text arrives — two beats, not one mush |
| "jab tak capsule ~60% target width tak nahi pahunch jaata, text/icon crossfade shuru hi nahi hota" | **implemented** | `gated(t, gate)`, Lab slider "content held back until the box is: 60 %" | no text ever shown at a width that clips it; slide it to 0 and it is the old fade |
| `SpringAnimation` / `androidx.dynamicanimation` | **adapted** | a closed-form curve in pure Kotlin | see §3 for why; the numbers he specified are all honoured |
| "damping ratio (overshoot control) aur response (target tak pahunchne ki speed)" | **implemented as the API** | `responseFor(style, profile, towardCard)`, `dampingFor(...)` — those two parameters, not mass/stiffness | the presets are literally response+damping pairs, so the Lab's three feels are his spec sheet |
| Arrival ~0.35 s ζ0.7 / tap-expand ~0.45 s ζ0.85 / auto-collapse ~0.30 s ζ1.0 / swipe velocity-handoff ζ0.9 | **implemented for three of four** | `dampingFor`: collapse 1.0, expand by profile (silky 0.85, snappy 0.9, bouncy 0.7); responses 0.28–0.48 s | a collapse that does not wobble at all, an expand that rings once |
| …swipe release-velocity handoff | **not this round** | the drag path (`IslandGesture`) does not yet hand a velocity to the morph; faking it from a fixed duration would have been the usual lie | — (next round's work; it is a gesture feature, not a style) |
| "corner radius height se bandha ho, alag se animate mat karo" | **implemented** | `tensionRadius(h, wanted)` — `min(h/2, wanted)` in `updateIslandLayoutForMorph` | mid-morph the thing stays a capsule instead of a rectangle wearing round corners |
| "Edge-aware asymmetric growth… punch-hole off-center" | **implemented + made testable** | `cutoutShift()` from `rootWindowInsets.displayCutout`, applied to the drawn box only | on his 11i the hole is centred, so the Lab ships a −40..40 dp bias to force the effect |
| "Metaball merge: do islands melt hoke ek blob" | **refused, with a reason** | §3 | — |
| "Firebase-driven motion profiles (snappy/silky/bouncy)" | **presets implemented, delivery refused** | the three profiles are radio buttons + stored prefs; no Remote Config in this build | §3 |
| Perf: "requestLayout() har frame pe mat karo, transforms use karo" | **already the house rule, and it constrained everything here** | `IslandMorphFrame` + `applyMorphFrame` — the pin is set once, phases change the *drawn* box; the blur is one quantised radius write | this is why both designs could be added at all without paying the b1343 frame-drop tax |
| "region update beech animation mein mat karo" | **held** | `setContentPinnedForMorph(true)` for the morph, restored at the end — unchanged since b1350 | — |

**One thing his design needed that the codebase actively eats:** spring overshoot. `IslandMorphFrame.compute`
clamps the drawn box to the pinned view, so an elastic curve is silently flattened — measured on b1373 as 29 of
46 frames moving the box by 0.0 %. So `beginMorphPerf` now widens the pin by `peakOvershoot(damping)` — the
analytic peak of the *exact* curve in use, width only — and `underdampedSpringOvershootsByExactlyWhatThePinAllowsFor`
asserts that the sampled peak and the widened bound agree, because the moment they don't, the bounce is gone
again and nobody will be able to tell why from looking at it.

---

## 2. ChatGPT's design — "HyperMorph / Living Surface" → style 5, `HyperMorph (ChatGPT)`

| His claim | Verdict | Where it lives | What he can feel |
|---|---|---|---|
| Phase A "Compression: width 97 %, height 106 %, 30–45 ms" | **implemented, literally** | `compression/compressedWidth/compressedHeight` — a triangle over the first 15 % of travel, `1 − c` and `1 + 2c`, with the Lab's "phase A squeeze" slider at 3 % | the pill visibly loads tension before it opens, and only on the way out (see §5 of MOTION-RESEARCH for why a squeeze inside a clamp is a hitch) |
| Phase B "Bloom: 220–280 ms, spring, damping 0.82–0.9, overshoot very small" | **implemented** | `responseFor(HYPERMORPH, …) = 0.26 s`, `dampingFor = 0.86` | a fast, fat, almost-critically-damped opening |
| "cornerRadius = height/2, radius ko independently animate mat karo" | **implemented, shared with style 4** | same `tensionRadius` | — |
| Phase C "content migration — same element, same trajectory, no fade-replace" | **already the praised behaviour, kept as-is** | `applyMorphCarry`'s row offset + icon ride (b1378 mechanism) | the one phase where his design and the app agreed before I touched anything; content scale is deliberately OFF for this style so it *is* a migration and not a zoom |
| Phase D "micro-settle 100→102→100, elastic not trampoline" | **implemented as a consequence, not as an extra animation** | nothing adds a bump; it is the underdamped tail of the same spring, bounded by `peakOvershoot` | one settle, no ringing. `closingIsQuickerThanOpeningAndDoesNotBounce` pins ζ into his 0.82–0.9 band |
| Phase E "staged pill return: content exit 100–140 ms, container collapse 170–210 ms, settle 20–30 ms" | **implemented** | `morphOutBy = 0.65f` forced for this style on collapse, response 0.19 s | the card empties, *then* shrinks. His "contraction faster than expansion" is the test's name |
| "Magnetic content: velocity ∝ attraction to the pill's anchors" | **implemented** | `magnetic(t, pull) = t^(1+pull)` applied to the row's own offset, Lab slider "pill pulls the content home by" | the content hangs, then is yanked into the pill. 0 % is exactly today's behaviour, which is the point of a knob |
| "Energy ripple: scale + clip + slight blur, 40–70 ms, feel not see" | **implemented (borrowing the glass plumbing)** | `ripple()` drives a 1.2 % breath on the box and `setGlassBlur(rip * morphBlurPx)` | a wet flash across the surface as it opens; reuses the quantised `RenderEffect` so it costs no new per-frame work |
| "never drive container/icon/text/controls on one curve" | **implemented as the architecture** | box = spring, corners = height rule, text = gate, row = magnetic, blur = ripple. Five laws, one clock | this is the sentence that best describes what the old styles were missing |
| "explicit state machine whose per-state properties an agent can read" | **already exists; extended, not duplicated** | `IslandStage` + the `[MORPH]` trace, which now prints `variant=… profile=… response=… damping=… overshoot=…px gate=…% magnet=…% squeeze=…% cutout=…px` | every judgement he makes can be tied to the numbers that produced it |
| "HyperMotion Protocol as canonical spec" | **adapted** | this file is the spec for the *motion*, `MOTION-RESEARCH.md` the history; no new protocol format | — |
| Compose `sharedBounds()/sharedElement()`, `ScaleToBounds` | **not applicable** | this app is Views + overlay windows, no Compose anywhere in `app/` | §3 |

---

## 3. Three things refused, on purpose

1. **`SpringAnimation` (androidx.dynamicanimation).** Two reasons, one practical: `app/build.gradle.kts`
   carries junit and nothing else, and adding a dependency to tune a curve is a worse trade than 40 lines of
   closed-form math that a JVM test can pin. The other: his snippet animates `lp.width` per frame, which is the
   exact per-frame layout of a screen-sized overlay that produced b1343's "text finally sets with a snap". The
   *shape* of his model is in (`response`/`damping` are the API); the *mechanism* would have undone two rounds
   of performance work.
2. **Metaball merge of simultaneous islands.** There is one island surface in this app: a card view that draws
   a box. A melt needs two surfaces with a shared field (an offscreen blur + threshold, or a path blend across
   two views) and a compositor budget that a 120 Hz overlay on a 2022 mid-range Xiaomi does not have. Shipping
   a fake of this — e.g. two boxes drawn slightly overlapping — would have been the "4th option that is the 3rd
   option" mistake again, wearing a fancier name. When multiple concurrent islands become a feature, the merge
   becomes possible; until then this line is the receipt.
3. **Firebase Remote Config for the presets, and Compose shared-bounds.** No backend exists in this build and
   no Compose exists in the project. The *presets* are shipped as prefs with the same three names, so if a
   config service ever arrives, `responseFor`/`dampingFor` are already the seam — the delivery is plumbing, not
   a redesign. A build that phones home for its feel would also stop being testable offline, which is how he
   uses it.

---

## 4. How to run the comparison he asked for

TestLab → Island Morph Lab:

1. **COMPARE A ⇄ B** (`btnCompareMotion`): selects style 4, plays a real morph (open, then close), selects style
   5, plays again — one tap, both designs, back to back. The picker is left on whichever played last, so "B
   wins" needs no second trip through the list. It replays through the accessibility service, not a mock-up: a
   preview of a curve is not a verdict about it.
2. **Feel preset** (snappy / silky / bouncy) — the two designs' response+damping table. Nothing else.
3. **Four sliders**, each labelled with its author's idea: phase A squeeze (0–8 %), content held back until
   (0–90 %), pill pulls the content home by (0–150 %, needs the drop entry — the centred entry lets the host own
   the row's Y and the pull would fight it, so it is not applied), grow away from the camera (−40..40 dp).
   At 0 / 60 / 0 they reproduce the plain spring morph; the defaults are the two designs' published numbers.
4. **REPLAY MORPH** still does one morph of whatever style is selected, for judging a single look.
5. If two options ever feel identical again, export the trace: the `[MORPH] start …` line names the variant that
   actually ran. If `variant=` and the radio ever disagree, that is the bug — and now it is also a test failure.

## 5. What each design got wrong about this codebase (so the next prompt can be sharper)

* Both assumed the container could be resized per frame. It cannot, here, for the reason in §3.1; anything that
  needs more size than the pin allows must ask for a wider pin, which is why `overshoot=Npx` is in the log.
* Both ignored that the fade already exists on a *shape-derived* progress (`MorphCarry.shapeProgress`), which is
  why the gate had to be written against that progress and not against `t` — and why the cutout nudge is applied
  after it, never inside `lastMorphBoxLeft`.
* ChatGPT's phase timings are for a phone with a compositor that will do 8 px blurs for free; on this panel the
  cheap version of "blur" is a quantised radius on three text views, which is what shipped.
* Claude's spec sheet was the single most useful paragraph either reply produced: naming *response* and *damping*
  per trigger is what made the presets testable rather than vibes.
* Neither asked what happens to the row's centring when the box changes height — which is where the vertical
  jitter was fixed in b1343/b1350, and why the compression phase is expand-only and the pin is widened on width
  only.
