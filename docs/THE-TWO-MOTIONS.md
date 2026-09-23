# The two motions, exactly

Every number in here was computed from the shipped functions (`core/MotionVariant.kt` read, not
remembered) for **this device**: pill 366 x 104 px, card 1067 x 421 px, `hz=60` from his own trace,
windows 380 ms out / 340 ms back, `lerpEven` pixel rounding applied. Frames are wall-clock frames: one
frame is 16.7 ms, so a "feel" under ~4 frames is not a feel, it is a dropped frame.

This document exists because his round-29 verdict was:

> jaisa mujhe laga tha waisa to kuchh nahi dikh rha hai, tumhe hi batao ki ye dono new animation exactly
> kaise hai, un AIs ne kaise bola tha, tumne kya kiya?

So each row is three columns: **the spec said** (quoted from the two designs), **the code does** (a
function and a constant that can be checked), **the phone shows** (px and frames at 60 Hz). Where the
third column is "nothing", it says nothing, and why.

## 1. Liquid capsule - style 4, "Claude's design"

One spring drives the box. Content is not cross-faded at the start: it is *gated* on the shape, and it
rides in from the pill's edge. Radius is `height / 2`, never animated on its own.

| the spec said | the code does | the phone shows (366 -> 1067 px, 60 Hz) |
|---|---|---|
| "single continuous morph, 380-450 ms" | `morphWindowFor` floors the spring styles at `MIN_MORPH_WINDOW_MS = 340`, expand runs the stage's 380 | 23 frames, no phase cuts |
| "one spring, response 0.30-0.34 s" | `responseScaleFor(LIQUID, *) = 0.72..0.85` of the window -> 274-323 ms at 380 | the box is at 5-25 % of its size on frame 1, not 60 % - the travel is spread over the window instead of dumped in its first third |
| "overshoot 1.02-1.08, then settle" | `dampingFor` 0.95 / 0.80 / 0.62 per profile | snappy +1 px, silky +11 px, **bouncy +59 px past 1067** then back over 12 frames |
| "content fades in at ~60 % of target width" | `gated(shapeProgress, morphGate)`, gate slider 0-90 % (default 60) | alpha 0.00 through frame 4, 0.33 at 5, 1.00 at 8 on bouncy - the shape visibly leads the content |
| "content translates along the shared edge" | `applyMorphCarry` -> row Y = -(leftover x (1 - shape)) | -205 px on frame 0 up to 0, tracking the box's bottom edge, not a clock |
| "radius = height / 2, clamp, do not animate independently" | `tensionRadius(bh, r)` capped at 66 px | 52 px on the pill, 66 from frame 2 on, 66 held through the overshoot |
| "collapse: zeta 1.0, no bounce, content exits early" | `dampingFor(*, false) = 1.0`, `goneBy = 0.45` | content at 0.69 / 0.17 / 0.00 by frame 3 while the box is still 970 px wide; box lands on frame ~12, no wobble |

## 2. Hyper morph - style 5, "ChatGPT's design"

Same box physics, different identity: the look is in **what happens around the shape** - a squash on the
way out, a blur ripple, and a settle tap - and on a collapse that *pulls* its content into the pill
instead of fading it in place.

| the spec said | the code does | the phone shows |
|---|---|---|
| "morph 240-320 ms, response 0.20 s, damping 0.82-0.9" | `responseScaleFor(HYPERMORPH) = 0.62`, `dampingFor = 0.86` | arrives 0.90 on frame 7, lands 1067 on frame 10, +27 px of overshoot total - faster and flatter than the liquid capsule's arrival |
| "Phase A: container compression 97 % W / 106 % H, 30-45 ms" | `compression(tc, COMPRESSION_WINDOW = 0.20, morphSqueeze)`, expand only, Lab 0-8 % (default 5) | frame 4 the box is **756 x 324** where the same spring with no squeeze gives 788 x 294: **32 px narrower, 30 px taller**, peaking at 38 x 30 on frame 5, then it opens out. At the 3 % his Lab has stored the same frame is 772 x 312 - 16 x 18: still over the ~10 px threshold, half the default's |
| "Phase B: energy ripple, blur pass, 40-70 ms" | `ripple(tc, RIPPLE_WINDOW = 0.20)` -> `setGlassBlur(rip x blur x 0.45)`, expand only | 4 frames of blur, peak 7.3 px, on the text only (the three content views + the action strip), quantised so it costs no extra pass |
| "Phase D: micro-settle 100 -> 102 -> 100" | `microSettle(tc, SETTLE_WINDOW = 0.30, max(1.5 %, squeeze/2))` | frames 15-21: 1068 -> **1093** -> 1068 px, one visible tap on the container after the shape has arrived |
| "collapse: content is *pulled* toward the pill, magnetic" | `magnetic(1 - open, morphMagnet)` at the default 0.6, `goneBy` forced to 0.65 | row Y 0 -> -11 -> -51 -> -100 -> -140 -> -205 across frames 1-6 while alpha drops 1.00 -> 0.76 -> 0.36 -> 0.02 over frames 1-3: the text moves as it leaves, it does not evaporate |
| "no squash on the way back" | compression, ripple and settle are inside `if (morphCarryGrowing)` | the collapse is a clean fast shrink - and that asymmetry is what makes the two styles recognisably not the same feature |

## 3. The frames, all of them

`spring` is the curve's value (1.000 = exactly the card); `box drawn` is the width and height actually handed to layout after pixel rounding and after this style's phases; `shape` is how far through the *size* the box is (what the content gate reads); `row Y` is where the content row sits relative to its final place; `blur` is the RenderEffect radius on the content views. Rows are every other frame, plus 1/3/5/7 where the phases live. A liquid row shows 0 % for squeeze/ripple/settle because those three belong to the other style, on purpose - that is the whole difference between them.

**LIQUID / snappy / expand  (window 380 ms, 23 frames, response 323 ms = 0.85 x window, damping 0.95)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.046 | 398 x 118 | 59 | 0.05 | 0.00 | -196 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.09 | 0.149 | 470 x 152 | 66 | 0.15 | 0.00 | -175 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.14 | 0.274 | 558 x 192 | 66 | 0.27 | 0.00 | -149 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.18 | 0.400 | 646 x 232 | 66 | 0.40 | 0.00 | -123 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.23 | 0.516 | 728 x 268 | 66 | 0.52 | 0.00 | -99 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.27 | 0.617 | 800 x 300 | 66 | 0.62 | 0.05 | -78 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.32 | 0.702 | 858 x 328 | 66 | 0.70 | 0.25 | -61 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.36 | 0.772 | 908 x 350 | 66 | 0.77 | 0.43 | -46 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.45 | 0.871 | 978 x 380 | 66 | 0.87 | 0.68 | -26 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.55 | 0.931 | 1018 x 400 | 66 | 0.93 | 0.83 | -14 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.64 | 0.964 | 1042 x 410 | 66 | 0.96 | 0.91 | -7 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.73 | 0.982 | 1056 x 416 | 66 | 0.98 | 0.96 | -3 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.82 | 0.992 | 1062 x 418 | 66 | 0.99 | 0.98 | -1 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 0.91 | 0.996 | 1064 x 420 | 66 | 1.00 | 0.99 | -1 | 0.0 | 0.0% | 0.00 | 0.0% |
| 22 | 1.00 | 1.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1068 px (+1 past the 1067 target), undershoot 398 (+32), frames with visible motion 17/22, blurred frames 0, content starts frame 6


**LIQUID / silky / expand  (default profile)  (window 380 ms, 23 frames, response 304 ms = 0.80 x window, damping 0.80)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.053 | 404 x 122 | 61 | 0.05 | 0.00 | -194 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.09 | 0.173 | 488 x 160 | 66 | 0.17 | 0.00 | -169 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.14 | 0.321 | 592 x 206 | 66 | 0.32 | 0.00 | -139 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.18 | 0.470 | 696 x 254 | 66 | 0.47 | 0.00 | -108 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.23 | 0.604 | 790 x 296 | 66 | 0.60 | 0.01 | -81 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.27 | 0.719 | 870 x 332 | 66 | 0.72 | 0.30 | -58 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.32 | 0.810 | 934 x 362 | 66 | 0.81 | 0.53 | -39 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.36 | 0.880 | 984 x 384 | 66 | 0.88 | 0.70 | -24 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.45 | 0.967 | 1044 x 410 | 66 | 0.97 | 0.92 | -7 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.55 | 1.004 | 1070 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.64 | 1.015 | 1078 x 426 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.73 | 1.014 | 1078 x 426 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.82 | 1.010 | 1074 x 424 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 0.91 | 1.005 | 1072 x 424 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 22 | 1.00 | 1.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1078 px (+11 past the 1067 target), undershoot 404 (+38), frames with visible motion 13/22, blurred frames 0, content starts frame 5


**LIQUID / bouncy / expand  (window 380 ms, 23 frames, response 274 ms = 0.72 x window, damping 0.62)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.066 | 414 x 126 | 63 | 0.07 | 0.00 | -191 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.09 | 0.221 | 522 x 174 | 66 | 0.22 | 0.00 | -159 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.14 | 0.412 | 656 x 236 | 66 | 0.41 | 0.00 | -120 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.18 | 0.600 | 788 x 294 | 66 | 0.60 | 0.00 | -82 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.23 | 0.765 | 902 x 346 | 66 | 0.76 | 0.41 | -48 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.27 | 0.894 | 994 x 388 | 66 | 0.90 | 0.74 | -21 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.32 | 0.986 | 1058 x 418 | 66 | 0.99 | 0.97 | -3 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.36 | 1.044 | 1098 x 436 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.45 | 1.083 | 1126 x 448 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.55 | 1.066 | 1114 x 442 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.64 | 1.035 | 1092 x 432 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.73 | 1.010 | 1074 x 424 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.82 | 0.997 | 1066 x 420 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 0.91 | 0.993 | 1062 x 420 | 66 | 0.99 | 0.98 | -1 | 0.0 | 0.0% | 0.00 | 0.0% |
| 22 | 1.00 | 1.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1126 px (+59 past the 1067 target), undershoot 414 (+48), frames with visible motion 18/22, blurred frames 0, content starts frame 5


**LIQUID / silky / collapse  (window 340 ms, 21 frames, response 163 ms = 0.48 x window, damping 1.00)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.140 | 970 x 378 | 66 | 0.86 | 0.69 | -9 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.10 | 0.376 | 804 x 302 | 66 | 0.62 | 0.17 | -43 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.15 | 0.584 | 658 x 236 | 66 | 0.42 | 0.00 | -87 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.20 | 0.736 | 552 x 188 | 66 | 0.27 | 0.00 | -125 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.25 | 0.838 | 480 x 156 | 66 | 0.16 | 0.00 | -154 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.30 | 0.903 | 434 x 136 | 66 | 0.10 | 0.00 | -174 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.35 | 0.943 | 406 x 122 | 61 | 0.06 | 0.00 | -187 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.40 | 0.967 | 390 x 116 | 58 | 0.03 | 0.00 | -194 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.50 | 0.989 | 374 x 108 | 54 | 0.01 | 0.00 | -201 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.60 | 0.997 | 368 x 106 | 53 | 0.00 | 0.00 | -204 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.70 | 0.999 | 368 x 104 | 52 | 0.00 | 0.00 | -204 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.80 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.90 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 1.00 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1068 px (+702 past the 366 target), undershoot 366 (-701), frames with visible motion 11/20, blurred frames 0, content starts frame 0


**HYPERMORPH / expand (squeeze 5 = default)  (window 380 ms, 23 frames, response 236 ms = 0.62 x window, damping 0.86)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.081 | 422 x 134 | 66 | 0.08 | 0.00 | -188 | 4.9 | 1.1% | 0.65 | 0.0% |
| 2 | 0.09 | 0.251 | 536 x 195 | 66 | 0.25 | 0.00 | -154 | 7.3 | 2.3% | 0.99 | 0.0% |
| 3 | 0.14 | 0.435 | 656 x 261 | 66 | 0.44 | 0.00 | -116 | 6.2 | 3.4% | 0.84 | 0.0% |
| 4 | 0.18 | 0.600 | 755 x 322 | 66 | 0.60 | 0.00 | -82 | 2.1 | 4.5% | 0.28 | 0.0% |
| 5 | 0.23 | 0.732 | 842 x 365 | 66 | 0.73 | 0.33 | -55 | 0.0 | 4.3% | 0.00 | 0.0% |
| 6 | 0.27 | 0.831 | 918 x 391 | 66 | 0.83 | 0.58 | -35 | 0.0 | 3.2% | 0.00 | 0.0% |
| 7 | 0.32 | 0.900 | 978 x 406 | 66 | 0.90 | 0.75 | -20 | 0.0 | 2.0% | 0.00 | 0.0% |
| 8 | 0.36 | 0.945 | 1021 x 411 | 66 | 0.95 | 0.87 | -11 | 0.0 | 0.9% | 0.00 | 0.0% |
| 10 | 0.45 | 0.991 | 1060 x 418 | 66 | 0.99 | 0.98 | -2 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.55 | 1.004 | 1070 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.64 | 1.005 | 1070 x 424 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.73 | 1.003 | 1078 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.7% |
| 18 | 0.82 | 1.002 | 1093 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 2.4% |
| 20 | 0.91 | 1.001 | 1090 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 2.0% |
| 22 | 1.00 | 1.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | -0.0% |
drawn peak 1094 px (+27 past the 1067 target), undershoot 422 (+56), frames with visible motion 17/22, blurred frames 4, content starts frame 5


**HYPERMORPH / expand (squeeze 3 = what his Lab has stored)  (window 380 ms, 23 frames, response 236 ms = 0.62 x window, damping 0.86)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.081 | 424 x 133 | 66 | 0.08 | 0.00 | -188 | 4.9 | 0.7% | 0.65 | 0.0% |
| 2 | 0.09 | 0.251 | 541 x 191 | 66 | 0.25 | 0.00 | -154 | 7.3 | 1.4% | 0.99 | 0.0% |
| 3 | 0.14 | 0.435 | 665 x 254 | 66 | 0.44 | 0.00 | -116 | 6.2 | 2.0% | 0.84 | 0.0% |
| 4 | 0.18 | 0.600 | 769 x 311 | 66 | 0.60 | 0.00 | -82 | 2.1 | 2.7% | 0.28 | 0.0% |
| 5 | 0.23 | 0.732 | 857 x 353 | 66 | 0.73 | 0.33 | -55 | 0.0 | 2.6% | 0.00 | 0.0% |
| 6 | 0.27 | 0.831 | 930 x 382 | 66 | 0.83 | 0.58 | -35 | 0.0 | 1.9% | 0.00 | 0.0% |
| 7 | 0.32 | 0.900 | 986 x 400 | 66 | 0.90 | 0.75 | -20 | 0.0 | 1.2% | 0.00 | 0.0% |
| 8 | 0.36 | 0.945 | 1024 x 408 | 66 | 0.95 | 0.87 | -11 | 0.0 | 0.5% | 0.00 | 0.0% |
| 10 | 0.45 | 0.991 | 1060 x 418 | 66 | 0.99 | 0.98 | -2 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.55 | 1.004 | 1070 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.64 | 1.005 | 1070 x 424 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.73 | 1.003 | 1075 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.4% |
| 18 | 0.82 | 1.002 | 1083 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 1.4% |
| 20 | 0.91 | 1.001 | 1081 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 1.2% |
| 22 | 1.00 | 1.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | -0.0% |
drawn peak 1084 px (+17 past the 1067 target), undershoot 424 (+58), frames with visible motion 15/22, blurred frames 4, content starts frame 5


**HYPERMORPH / collapse (magnet 60 default, goneBy 65%)  (window 340 ms, 21 frames, response 153 ms = 0.45 x window, damping 0.95)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.158 | 956 x 372 | 66 | 0.84 | 0.76 | -11 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.10 | 0.418 | 774 x 288 | 66 | 0.58 | 0.36 | -51 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.15 | 0.638 | 620 x 220 | 66 | 0.36 | 0.02 | -100 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.20 | 0.791 | 514 x 170 | 66 | 0.21 | 0.00 | -140 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.25 | 0.885 | 446 x 140 | 66 | 0.11 | 0.00 | -169 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.30 | 0.940 | 408 x 124 | 62 | 0.06 | 0.00 | -186 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.35 | 0.970 | 388 x 114 | 57 | 0.03 | 0.00 | -195 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.40 | 0.986 | 376 x 108 | 54 | 0.01 | 0.00 | -200 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.50 | 0.997 | 368 x 106 | 53 | 0.00 | 0.00 | -204 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.60 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.70 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.80 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.90 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 1.00 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -205 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1068 px (+702 past the 366 target), undershoot 366 (-701), frames with visible motion 9/20, blurred frames 0, content starts frame 0


**HYPERMORPH / collapse (magnet 150 + drop 40 = loud)  (window 340 ms, 21 frames, response 153 ms = 0.45 x window, damping 0.95)**
| f | t | spring | box drawn W x H | radius | shape | content a | row Y | blur | squeeze | ripple | settle |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 0.00 | 0.000 | 1068 x 422 | 66 | 1.00 | 1.00 | -0 | 0.0 | 0.0% | 0.00 | 0.0% |
| 1 | 0.05 | 0.158 | 956 x 372 | 66 | 0.84 | 0.76 | -1 | 0.0 | 0.0% | 0.00 | 0.0% |
| 2 | 0.10 | 0.418 | 774 x 288 | 66 | 0.58 | 0.36 | -12 | 0.0 | 0.0% | 0.00 | 0.0% |
| 3 | 0.15 | 0.638 | 620 x 220 | 66 | 0.36 | 0.02 | -34 | 0.0 | 0.0% | 0.00 | 0.0% |
| 4 | 0.20 | 0.791 | 514 x 170 | 66 | 0.21 | 0.00 | -58 | 0.0 | 0.0% | 0.00 | 0.0% |
| 5 | 0.25 | 0.885 | 446 x 140 | 66 | 0.11 | 0.00 | -78 | 0.0 | 0.0% | 0.00 | 0.0% |
| 6 | 0.30 | 0.940 | 408 x 124 | 62 | 0.06 | 0.00 | -90 | 0.0 | 0.0% | 0.00 | 0.0% |
| 7 | 0.35 | 0.970 | 388 x 114 | 57 | 0.03 | 0.00 | -97 | 0.0 | 0.0% | 0.00 | 0.0% |
| 8 | 0.40 | 0.986 | 376 x 108 | 54 | 0.01 | 0.00 | -101 | 0.0 | 0.0% | 0.00 | 0.0% |
| 10 | 0.50 | 0.997 | 368 x 106 | 53 | 0.00 | 0.00 | -104 | 0.0 | 0.0% | 0.00 | 0.0% |
| 12 | 0.60 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -105 | 0.0 | 0.0% | 0.00 | 0.0% |
| 14 | 0.70 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -105 | 0.0 | 0.0% | 0.00 | 0.0% |
| 16 | 0.80 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -105 | 0.0 | 0.0% | 0.00 | 0.0% |
| 18 | 0.90 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -105 | 0.0 | 0.0% | 0.00 | 0.0% |
| 20 | 1.00 | 1.000 | 366 x 104 | 52 | 0.00 | 0.00 | -105 | 0.0 | 0.0% | 0.00 | 0.0% |
drawn peak 1068 px (+702 past the 366 target), undershoot 366 (-701), frames with visible motion 9/20, blurred frames 0, content starts frame 0

## 4. What is deliberately doing nothing on his phone

* **The cutout dodge.** `cutoutShift` returns 0.0 for his lens: `cutout=540/40px` is horizontally inside a
  366 px pill and inside every width of the card, and a sideways nudge cannot uncover a hole you are
  already sitting on. The bias slider (0-40 dp) is what makes it produce a shift; at 0 there is no drift.
  "It never shifts sideways" is the containment guard working, not the feature being dead.
* **The `excess=` field** in `[MORPH] start` prints 0 for the four classic styles by construction
  (`viewExcessHalf` is only non-zero where a spring needs the headroom) - that is the invariant that
  protects the ride he already accepted.
* **The glass blur** on the liquid capsule is off in style 4 by design: the ripple is style 5's, and
  reusing glass blur as a ripple produced the fog flash he called glitchy.

## 5. Two defects this doc found, and what they cost

Writing the tables, rather than describing the features, turned up two things in the shipped build -
both now fixed in `27e2eb8`, both covered by tests:

1. **The auto-notification morph sized its spring from the wrong number.** `beginMorphPerf` was handed
   720 ms - the total of a sequential ping (120) plus expand (360) - while the animator that owns the
   shape ran 360. A spring sized for 720 and stopped at 360 is at ~0.86 of its travel on the last frame,
   and the interpolator pins the final frame to 1.0. The whole morph therefore *snapped* at the end, on
   the one path he uses most. Each call site now takes its window from its own animator.
2. **The timed phases were measured on the shape, which is a spring.** `progressOf` is 0.11 after one
   frame and 0.32 after two, so a 30-45 ms compression was sampled once, past its peak, and the ripple's
   40-70 ms became one blurred frame. They now run on the animator's clock; only the gate stays on the
   shape, because it was specified as a share of the target width.

And the honest summary of round 28's tuning: the responses I shipped (155-215 ms inside a 380 ms window)
reached 90 % of the size in five frames and spent the remaining eighteen drifting a dozen pixels.
Measured on the frame table: **9 of 23 frames moved at all**. The specs' *ratios* (0.80 x window for
Claude, 0.62 x for GPT) are what fill a 60 Hz window; b1411 had converted them to absolute milliseconds
and in doing so had thrown the motion away. That is the arithmetic behind "dono new options ek he hai" -
and it is the reason both are now asserted against the curve, not written in prose.

## 6. To see all of it at once, in TestLab

* Motion style: **Liquid capsule (Claude)** vs **Hyper morph (ChatGPT)**, A/B them by switching once each.
* Spring profile: **bouncy** (the only preset whose overshoot is unmissable at 60 Hz - 59 px).
* Squeeze: **8** (the top). 8 gives ~63 px narrower and ~48 px taller at the peak, against 32 x 30 at the default 5 and 16 x 18 at his stored 3 - the width part of a small squeeze is less than people expect, the height part (+106 % as specified) is what the eye catches.
* Gate: **85** so the content visibly waits for the shape; 0 makes the two styles much harder to tell apart.
* Magnet **150** and content drop **40** on the collapse - the magnetic pull is 205 px of travel at those
  values instead of the 60 %-default's gentle lag.
* The squeeze slider's stored value overrides the new default: if it reads 3, that is what runs.

## 7. What is still unproven

`style=glass settle` appears **0 times** in his b1406 log, so that look has never been judged by him - it
is not confirmed working, only confirmed not-crashing. The b1401 ride verdict, the round-25 items and the
A-vs-B comparison are also still owed by him. This doc claims what the frames do, not what he will feel.
