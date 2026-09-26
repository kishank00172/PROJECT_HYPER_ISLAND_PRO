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
| "one spring, response 0.30-0.34 s" | `responseScaleFor(LIQUID, *) = 0.72..0.85` of the window -> 274-323 ms at 380 | the box is at 5-7 % of its size on frame 1 (b1411: 18 % there, 60 % by frame 3) - the travel is spread over the window instead of dumped in its first third |
| "overshoot 1.02-1.08, then settle" | `dampingFor` 0.95 / 0.80 / 0.62 per profile | snappy +1 px, silky +11 px, **bouncy +59 px past 1067** then back over 12 frames |
| "content fades in at ~60 % of target width" | `gated(shapeProgress, morphGate)`, gate slider 0-90 % (default 60) | alpha 0.00 through frame 4, 0.41 at 5, 0.74 at 6, 1.00 at 8 on bouncy - the shape visibly leads the content |
| "content translates along the shared edge" | `applyMorphCarry` -> row Y = -(leftover x (1 - shape)) | -205 px on frame 0 up to 0, tracking the box's bottom edge, not a clock |
| "radius = height / 2, clamp, do not animate independently" | `tensionRadius(bh, r)` capped at 66 px | 52 px on the pill, 61 on frame 1, 66 from frame 2 on and held through the overshoot |
| "collapse: zeta 1.0, no bounce, content exits early" | `dampingFor(*, false) = 1.0`, `goneBy = 0.45` | content at 0.69 / 0.17 / 0.00 by frame 3 while the box is still 970 px wide; box lands on frame ~12, no wobble |

## 2. Hyper morph - style 5, "ChatGPT's design"

Same box physics, different identity: the look is in **what happens around the shape** - a squash on the
way out, a blur ripple, and a settle tap - and on a collapse that *pulls* its content into the pill
instead of fading it in place.

| the spec said | the code does | the phone shows |
|---|---|---|
| "morph 240-320 ms, response 0.20 s, damping 0.82-0.9" | `responseScaleFor(HYPERMORPH) = 0.62`, `dampingFor = 0.86` | 0.90 on frame 7, crosses the 1067 target on frame 12, and its +27 px peak (1094 on frame 18) is the settle tap rather than the spring - faster and flatter than the liquid capsule's arrival |
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

## 7. Round 31: the axis, which should have been the first question

Round 30 ended with the bounce re-spent on height *only where the screen clipped it*. His reply was one sentence
and it was a better review than my last three rounds of tuning:

> left right spring effect kon dalta hai island mei? Motion upar niche hoti hai island mei, isliye spring effect
> bhi waisa he hona chahiye.

He had also asked whether the AIs really specified all this. On the one point that matters, **they did**:

* Claude: *"`capsule` ka width 1.02-1.08 tak overshoot kare, phir settle"* - the overshoot is on the width;
* ChatGPT: *"container scale 100 -> 102 -> 100"* - also a width pulse.

Both of them were describing a Dynamic Island, which floats with air on all four sides. This island **hangs from
the top edge of the screen and grows downward**: horizontally it has 1067 px of a 1080 px panel, so the whole
bounce budget on that axis is thirteen pixels, half of it per side, and the card's edges are already at the
screen's. Their timing, their gate, their squash, their ripple and their staged return all transfer to this device;
their horizontal bounce cannot, and it should never have been chased. Three rounds of mine went into making a motion
bigger on the one axis that cannot carry it.

So the rule is now in `MotionVariant.axisWidth`, and it is unconditional for the two spring styles: **the width only
ever arrives** - capped at the card's width going out, floored at the pill's coming back - and every elastic
component is spent downward, where the card has 1979 px of screen below it. That covers the spring's settle, the
ripple's breath and the settle tap, and the damping table was re-tuned for the new yardstick, because 421 px is not
1067 px and the same ratios would have shrunk the motion threefold:

| profile | damping | thickness added at the peak | frames of it at 120 Hz | reads as |
|---|---|---|---|---|
| snappy | 0.94 | **0 px** | 0 | a lid that lands and stays |
| silky | 0.78 | **+6 px** | ~4 | one soft settle |
| bouncy | 0.60 | **+30 px** | ~14 | the card swells and relaxes |
| hyper morph | 0.84 + tap | **+10 px** | ~7 | a tap after the shape has arrived |

The trace says so too: `[MORPH] start` now prints `axis=down travelV=30px down`, and `travelV` is computed the way
he will judge it - a share of the 104 -> 421 *growth*, not of the 421 the box lands on - with a test pinning that
number to the curve that produces it.

### What that deleted, which is most of the value

`visibleExcessRoomPx`, `wastedWidth`, `clampedWidth`, `WASTED_WIDTH_TO_HEIGHT_GAIN`, `morphRoomW`, the pin's
horizontal widening, `avoidRamp`, `cutoutShift`, `measureMorphCutout`, `morphBoxShiftPx` and the frame-copy that
applied it, the `dp(20)` cap, the row's width pin in `setContentPinnedForMorph` (height-only again, which is what it
was before any of the spring styles existed), and the Lab's "grow away from the camera" slider with its pref.
Five tests went with the machinery they pinned.

Two of those deletions are worth naming. The cutout rule goes not because it misfired but because a sideways nudge
is not a motion this surface can make, so the whole "edge awareness" idea was misapplied here - and the row's width
pin goes because the *only* reason it existed was that I had made the view wider than the card and had to stop the
content noticing. Fix the axis and the correction it needed disappears with it. That is the shape of the right fix:
it removed more than it added.

### The same trap, one axis over, caught before it shipped

`IslandMorphFrame.compute` clamps the drawn box to the view - the very clamp that silently ate the horizontal
bounce ("39 perfectly delivered frames drawing the same box"). A view pinned at 421 px tall cannot be drawn 461, so
if the axis swap had been the whole change, **the bounce would have been invisible for a fourth round running**. The
view now gets exactly as much extra height as the curve is expected to use (`morphPinH += the analytic peak`,
spring styles only). The centring rule that survived round 31 - content against the card's *natural* height - was
repaired and then re-broken within one build; §9 is that story, and the short version is: there are two bounds in
a headroom world, and the content has to be centred against the one the layout actually used, not the one the
card will end up at. With that fixed, the spring's peak leaves the content *standing still* and the overshoot is
nothing but empty surface below the card.
pins all four cases, including the no-headroom one, which must still come back clamped.

## 8. Round 30: what his first `glass settle` run said

68 morphs, and the verdict was: tap from the pill is **smooth**, but "content starting mei blur rehta hai, fully
expand pe clear and collapse mei bhi blur". The blur was my addition, not a design requirement: I had read
Apple's liquid-glass lensing as "defocus the island while it morphs", and implemented it on the views that
*draw* - the header, title, message and action strip. But their lensing bends the material **behind** a
translucent panel; this island has nothing behind it. The only thing my `RenderEffect` could bend was his text, so
he spent the whole expand and the whole collapse reading a defocused label. `morphBlurPx` is now 0 for the glass
style (`[MORPH] start` prints `glass=on defocus=cut`), the timing he praised is untouched, and the ripple keeps its
blur because GPT asked for that one in words. This is the second time a "material" idea of mine had to be cut
because on this surface material has nothing to be material about - the first was the metaball note in §3.

**And one thing from this log that worked:** `checkNotificationShadeState` appears **0 times** in 1131 lines (it
was 80 calls and 3 750 ms in b1406, inside 29 of 74 morph windows). The off-main-thread shelf probe passed its
acceptance test with nobody watching it.

## 9. Round 32: two bounds, and the content belongs to the one the layout used

b1422 got the axis right and the content wrong, in one mechanism, and his report was two symptoms that look
unrelated until you do the arithmetic: "spring effect content pe jerky feel deta hai" and "wapas jab pill banta
hai to icon gayab ho jata hai, theek karo - old teeno mein bhi".

**The jerky spring.** `IslandMorphFrame.compute` translated every child by `(boxH - naturalH) / 2`, but the view
is `naturalH + headH` tall and the layout centred the content in the full view. Two references, one truth short:
at the settle the content stood half the headroom out of place (a permanent +15 px at the bouncy profile), every
frame the curve crossed the natural height the offset flipped sign, and since the hand-off resized the view and
reset the translation in one call, the last frame of every spring morph snapped the content 15 px straight up.
His words for the whole class: jerky, not smooth. The fix is one sign and one sentence - the content is centred
against the surface it is *laid out in* (`(boxH - boundH - headH) / 2`), not against the card's destination:
then the offset
reaches its rest value (`-headH/2`, exactly cancelled by the layout's own `+headH/2` leftover) at the natural
height and never moves again: the curve's whole elastic excursion is drawn as surface below a content standing
still, and below the natural height every child's drawn position is - truncation aside - bit-identical to the
headroom-free curve of the classic styles. The ride curve in
`applyMorphCarry` subtracts the same `headH/2`, so both writers land on one number at open = 1.
`theHeadroomIsInvisibleToTheContentForEveryBoxHeight` checks both halves of that claim against the
same five box heights and three content heights. At the bouncy peak b1422 dipped the CONTENT 30 px with a 15 px end-snap; now the content's peak
excursion is the integer rounding noise, and only the bottom edge moves.

**The icon that vanishes on the way back.** Nothing to do with the spring: `setStageAnimated` faded the *pill
preview* out (`alpha = 1f - t`) on every morph target except the ping pill - including the collapse to idle, in
which the pill preview IS the destination. So from ~40% of a manual collapse (when the card's content has faded
out per the goneBy rule) the box shrank around an empty capsule, and at the end the idle-collapse listener set
the pill `GONE` entirely, leaving the icon's return to a later notification-queue repaint - which, on the two
occasions it did not come in his own b1415 export, left `end-state grid=V/0.0 pill=G/0.0`: a black capsule with
no icon at all. Rule the round adds to the file: **an animation's destination must exist from frame one**. The
collapse now pre-shows the pill (visible, opaque, on top - the same treatment the ping branch always had), the
fade runs only on the way OUT of the pill, and the end of the collapse keeps what the morph arrived at instead
of handing the surface back to a queue.

Both fixes leave the four classic styles' numbers exactly where they were - headH is zero and the pill rules are
direction rules - which is also how I know the "old teeno" were collateral, not target: when the destination
doesn't exist, no style can ride into it, balanced or bouncy.

## 10. Round 33: the meaning of a formula outlives the world it was written for

His b1424 report came with the log: liquid with his saved snappy profile (`damping=1.00`, `travelV=0px`),
collapses to the ping pill, and three observations that are three different bugs - plus one ask by name.

**The icon vanished mid-collapse because a subtraction had changed meaning.** `boxLeft - (pinW - finalW) / 2`
was written when the view could be WIDER than the card: the difference was headroom and the subtraction put
anchors back onto the box. After the axis rule there is no horizontal headroom, so on a collapse the difference
is the whole travel: the pill's own icon began every trip ~350 px off the left edge of the view and slid in
only for the last frames, and the fading row was dragged the same 350 px to the right. The fix is not a new
constant, it is the removal of a stale one (`viewExcessHalf` is gone, with its tests and its trace line): the
pill copy glues to the box's own left edge on every morph in every style, and the row's x translation is a
literal 0.

**The shared element was being faded by the container it leaves - again.** `setMorphContentAlpha` wrote the
whole row, so `goneBy = 45%` erased the riding icon half-way into every collapse. The fade now lives on the
text section only: the text still exits early (that was measured on hardware and stays), while the icon rides
the whole way down, swaps to the pill's glyph, and the pill's own copy - riding from frame one but held back
to the last 15% - materialises underneath to take it over. The count badge pops in with it only when the morph
ENDS (beginMorphPerf hides it, the cleanup restores it through the one policy writer; his ring held 11 pages,
which is why it popped at frame one the moment the destination became visible).

**The ask: buoyancy.** "Content sidha aa jata hai, uspe effect daalna hai - buoyancy wala, jaise content liquid
mein hai aur expand ne uspe asar daala." With the box critically damped at his saved profile, the content's 1:1
ride was the only motion left, and 1:1 reads as teleportation at 380 ms. `MotionVariant.buoy` rides the content
on its own spring - response x 1.30, damping 0.55 - so on the way IN (spring styles only) it arrives after the
box, overshoots by 12.6% of the ride (~15 px at his `drop=20dp` setup), and floats back to its seat; the shared
spring clamps the endpoints exactly, so the hand-off still cannot snap, and the classic styles are untouched.
`buoyancyIsALagAndADipInsideExactEndings` pins the endpoint exactness, the bounded peak, and the settle; the
trace now prints `buoy=0.20s/0.55` so the curve on his phone is the same curve this paragraph claims.

§9's boundary rule, generalized: it is not just bounds this file keeps getting wrong about, it is *references*.
A formula's inputs can silently stop meaning what they meant when the formula was approved (an overshoot bound,
a headroom difference). Every correction that survived contact with his eyes reads the scene directly - the
box's own edges, the animator's own clock - and computes nothing from deltas of the delta.

## 11. Round 34: the expansion he saw was sequential; the one in the table is not - his spec reconciled both

His round-34 opening, with the angry emoji: "pehle y axis ke along expand hota hai, fir dono side expand hota
hai, uniformly all side expansion nahi hota kyu". Before defending or fixing anything, the table at his exact
params (ping pill -> full, liquid at his saved snappy profile, 380 ms, 60 Hz): `spring(t)` drives BOTH axes, so
at frame 6 of 23 the box is 1010 x 395 - 92 % each; at frame 9, 1057 x 416 - 99 % each. Nothing in the size
math is sequential: width moves 2.2x the pixels per frame (701 px to cover vs 317), so in the ~100 ms real
working window the sideways motion dominates the retina; by the time content clears the gate (60 % shape,
opened at frame ~3), the shape has already flattened. What he could not name is the part his spec then wrote
down for him: the expansion has no PULL. A card that blooms symmetric from the top edge without ever travelling
down reads as a resize, not as an island being opened - and a resize is exactly what "uniformly nahi" felt like.

So round 34 implements his volume-constant spec mechanism for mechanism, which also answers the uniformity
question with a single shared driver:

* **Step 1 - the pull and throw:** `pullPhase(t)` = one sine arc over the first 75 % of the clock, 0 at both
  ends. It feeds two properties at once: `islandView.translationY` peaking at **26 px** (10 dp) and the text
  column's `scaleY = 1 + 0.15 * arc`, `scaleX = 1 - 0.05 * arc` - his exact 1.15 / 0.95 pair, on the text only.
* **Step 2 - deep sink:** the island and the content travel together (the buoyancy of round 33 stacks with the
  arc: the content still dips its own 15 px past the seat while the box is sinking).
* **Step 3 - the snap back:** the arc returns to exactly 0 and the scales to exactly 1.000 by frame 17 of 23,
  because the endpoints are definitions, not hopes; the cleanup re-asserts every one of them.
* **Step 4 - separated buoyancy:** after the settle the island is rigid (it never moves again), the text column
  bobs **+- 3 px**, the icon **+- 1 px** - his amplitudes verbatim - on one shared 2.4 s sine, written per
  vsync to two hardware-layered views, so the loop costs no re-raster. The float starts at morph end on a full
  spring card and is retired (with the translations restored to 0) by the first line of the next beginMorphPerf.

Rules of ownership, because this file exists to keep them: the pull owns `islandView.translationY` alone and
nothing in `IslandMorphFrame` is allowed to know about it (the frame math centres the box, the pull moves the
window - two properties, or the centring and the sink fight); the stretch owns the text column's scale and the
icon is never in the column ("left ka icon rigid brand asset hai"); the float owns the content's translationY
and the icon's, and nobody else writes those two while it runs. The three spring styles get the pull and the
float (`isSpring` is the gate, as with the buoyancy: a rigid curve pulled downward reads as a window glitch,
not as elastic); the four classic styles are untouched by all of it.

The one honest limit the spec does not have: the pull arc is keyed to the CLOCK (t), not to the shape - his
steps are written in time ("tezi se neeche", 30-45 ms phases), and anything keyed to `spring(t)` at his
almost-settled profile would end before it could be felt. The trace prints it, so it is checkable on device:
`pull=10dp stretch=0.15/0.95 float=3/1px on`.

## 12. Round 35: a design gets its own home, and an anchor is never a volume to spend

Two instructions, both from watching b1426 actually run instead of reading its description: "uska new morph
banao - alag design, addon nahi", and "upar wala portion lock rahega - abhi dekhta hu ki during animation upar
wala area niche kuchh frames ke liye aa jata hai, maybe volume match karne ke liye, lekin usko lock kar do".

The first is an architecture rule the ledger needed anyway: the pull/stretch/buoyancy/float package is now ONE
style - `MORPH_STYLE_LIQUIDPULL` (6), named "liquid pull (aapka design)" - and the round-34 flags on liquid and
hypermorph are gone from them: they are back to their own designs, his is back to being his. He's right about
the principle, not just the taste: an addon layered on someone else's design is two designers at once, the
exact tangle the carry-and-fade years cost us. The flotation therefore gets gated by style, not by spring-ness:
liquid and hyper morph keep round 31-33 (axis rule, spring settle, the buoyancy he asked for them in round 33),
and the pull family belongs wholly to style 6.

The second is a physics correction with an anchor in it. The round-34 sink translated the whole view, so for
the ~10 frames near the arc's peak the island's TOP EDGE moved down 26 px. He measured it with his eyes and
proposed the right rule before I could: "upar wala portion lock rahega". Now the sink is paid entirely by the
BOTTOM edge: `bh += 26 px * pullPhase(t)`, the spring and pull headrooms are budgeted together into the pin
(spring + 26 px, so at his snappy profile the box may draw 457 px and the dip peak 443 fits), and the
contentOffset pin keeps the content still while the bottom edge travels: throw (+26 px by frame 9-10),
caught home by frame 17 - and `top = 0` on every frame of every amplitude, which the frame math has to keep
true anyway or the island would stop hanging from the anchor that makes it an island. The lesson that survives
this build: a displacement is defined by its anchor first and its amplitude second; my round-34 mechanism was
named after the amplitude.

Amendment (same round, after he watched this build too): "kuchh effect hai kaha - bas normally expand ho ja
raha hai, mera idea ignore kar diya?" Visibility is a property of the effect, not a wish. The single sine
peaked at frame 9, when the box itself was still racing downward 300 px - a 26 px dip on an edge already
travelling fast drowns in the travel. The arc was re-drawn where the eye can see it: quarter-sine attack
starting at t=0.12, a HANG at full depth from t=0.39 to t=0.61 (the depth must be met, not skimmed; frames
9-14 the bottom sits 26 px below its seat while the box is already full) and a quarter-cosine release that
accelerates into the seat - yank, hang, snap, over by frame 20. And the default style is now his design
itself: existing builds keep their pref (choice is respected), fresh installs start on HIS, because an idea
he was told exists but cannot find reads as an idea that was ignored.

The trace numbers are the audit: `pull=10dp stretch=0.15/0.95 float=3/1px on` - and now also a style name in
the same line, "liquid pull (aapka design)", so the design that runs is the one he picked in the Lab. His own
words about the spec - "ho sakta hai mere vision se thoda alag ho gya hoga, main test karne ke baad dekhunga" -
are respected by constants, not by persuasion: every number above is one `const val` in MotionVariant.

## 13. Round 36: blueprint v2, and the truth about why the pull was never visible

His instruction replaced an entire design in one line: "replace the 7th one - ye lo, ranking bhi chahiye." The
blueprint that arrived carries his correction about my answer style built into it ("faltu ka mat likhna"), so
this section holds three things and three only: what now lives on the style, what b1430 really showed, and the
binding constants.

**The bow at b1430 was bent twice.** The masking theory (a sink peaking mid-race drowns) was true but only the
second half of it: `morphVariantOn = morphLiquidOn || morphHyperOn` gated EVERY writer of the pull out of the
style it was supposed to serve, so on b1427/b1430 the dip was dead code on its own style (the stretch and the
float, living in wider-reaching functions, were their only running parts). His eye ("kuchh effect hai kaha")
read the code's truth even when the code did not. The takeaway is the same one the carry-and-fade era taught:
a feature whose writers sit inside another feature's gate is not a feature, it is a rumor.

**What replaced it is not patched-on, it is read-from-his-page.** Container: spring at ζ=0.86/response 0.22 s
his own math against his own table: that pair puts the "2-3 px" overshoot at 2.2 px of real travel and locks
by t = 0.187, comfortably before his "Container Lock" boundary of 0.337 (320 ms of 950) - the constants row's
0.78 would have shown ~8 px instead, and the table won because the table is what his eye reads. Text: an
anchor-pendulum (-40 arrival, +26 px sink at the lock, the -5 / +2 pair, rigid from 0.758-0.90) joined by
half-cosine arcs, because a pendulum's extremes are zero-velocity points, which is exactly what cos arcs
between them draw; his claim about the deep sink being "fixed, not content-scaled" landed as a const, not an
amplitude function. Icon: half the pendulum, 40 ms late, never stretched, zero before its delay (his "40 ms
starts after text" respected as a seat-pin, not a lazy ramp). Opacity and stretch own the pull window alone
(130 / 247 ms). One haptic at his lock boundary, exactly once per expand. Collapse runs his "asymmetric,
crisp" pair (380 ms, ζ=0.90 - 0.23 dp of invisible overshoot by the arithmetic). And she said it in her own
preamble, and it is now architecture: every number above is one `const val`, pinned by a test that reports
the constant back by name if a refactor drifts.

**Ranking, as asked.** Below in the answer, not here - this file holds mechanisms, not league tables - but
the criterion the table used is written down: (a) what his eyes praised, (b) how few complaints it earned,
(c) how many subsequent fixes each style consumed, (d) whose design it is. Blueprint v2 tops (b), (c) as a
hypothesis now, and (d) by definition; the rest of the table follows.

## 14. Round 37: the clock is not the carry's clock (D1), and two writers of one alpha (D2)

The audit he commissioned found its own villain in my code - and it was a legacy clock, the eldest kind of
bug in this family. Every expand reaches `applyMorphCarry`, and on the auto path the carry receives
`0.5f + 0.5f * t` (the ping half and the expand half each own half the timeline - a glyph hand-off contract
from the drag era, still correct FOR THE GLYPH). The blueprint v2 overlay consumed that value directly, so on
the path he expands most (auto notification) the pendulum ran t in [0.5..1]: the stretch window skipped, the
sink peak spent, and the haptic's window (0.335-0.345) - mathematically unreachable before t=0.5 - never
fired at all. Manual tap-expand, by passing the carry 1×t, was the only path showing anything. The fix
moves the overlay out of the carry's world and one step earlier into `updateIslandLayoutForMorph`, to the
tick every expand animator makes with its own untransformed `t`; the gate (v2-only, towardCard, pin-live)
keeps it out of the ping half and out of collapses. D2 went with it: the legacy shape-fade (default-on)
quietly re-wrote the one property the blueprint declares its own (the text section's alpha) on every frame
- now `&& !morphV2On` at both sites. Classic styles: zero change by construction; the only added predicate
is the v2 gate. And it is on record now: a clock transformed for one consumer is not a clock, it is half
an effect; reusing it for a second is how two mathematically correct effects ship nothing.

## 15. Round 38: prove it on the device (A), an over-gate walked backwards (B), and the island learns to sleep (C)

The combined audit's contract was explicit this time: runtime values, not static assurance. Three changes.

**A - instrumentation, honest uncertainty.** Statically, post-D1 both paths should fire: the tween's gate gets
its three values from begin, before any frame. That fact is now printed instead of asserted: the tween's first
pinned frame of every morph logs `frame1 gate evidence: v2On=..., toward=..., pinH=true, style=...`, and the
overlay's own first execution logs `v2 overlay firing`. If the device prints the first without the second, the
gate is the bug and its three values say which arm closed; if it prints both and nothing is visible, the next
variable is his device, and the trace line's t value brackets where it happens. Either way the next answer
arrives with numbers attached, which is the entire discipline the round was ordered under.

**B - the ghost was my own gate.** Round 37's D2 was written `&& !morphV2On` - by style, not by direction - so
on any blueprint COLLAPSE the shape-fade stepped off and the text's alpha had no writer at all: frozen visible
at 1.0 while the pill shrank underneath, which is precisely "ghost/masked content during collapse". The gate
is now by direction (`!(morphV2On && morphTowardCard)`): the blueprint owns alpha on expand, the fade owns it
home. Begin and end of every morph now log the content section's alpha/scale/translationY/layerType, so a
repeat is a one-line read. The stale-hardware-layer fear in their B.2 is N/A: Android re-renders hardware
layers on every property invalidation by design.

**C - the island sleeps instead of dying.** Every hide used to detach the whole window (`removeViewImmediate`
+ null), so every wake paid full inflation on the expansion's first frames - the cold-start that read "snappy".
Now `hideIslandInternal` GONEs the view, flags the window NOT_TOUCHABLE (0x10 logged as the audit's required
region proof), and keeps the window attached for the process's life; `ensureIslandAwake()` replaces the four
"null check means not-visible" guards, which were quietly wrong the moment non-null stopped meaning visible.
And the GPU layers are primed once per process right after the first build (`buildLayer()` at pill-state) so
texture allocation never lands mid-animation again. The state resets inside hide are byte-for-byte the
discard era's, so nothing that trusted them regresses.

## 16. Round 39: the interpolator's output is a shape, not a clock

The device said "plain resize, normal content" and the trace said "overlay firing" - both true, once. The log
that exposed it was of my own making (the t=0.000 on the very line claiming the path was active): `it
.animatedValue` returns the interpolated value, the morph's interpolator IS the spring, and the pendulum read
that number AS IF it were time. Computed exactly: with response 0.22 s / damping 0.86 on the 950 ms clock,
the spring output crosses 0.335 at real 39.6 ms (haptic window mostly never fires - it sits between frames 2
and 3), 0.40 at 45.3 ms, 0.90 at 113.0 ms, 1.000 at about 179 ms. Which means ALL of the pendulum's authored
anchors (-40, +26 sink, -5, +2, rigid) executed inside the animation's first ~7 frames of 57 - a 95 ms flicker
at the far start that reads as "content did not animate". Nothing was missing; the clock auction it off.
The mechanics of the same famous household: a curve's y-value is not its x-axis. The fix does not un-spring
anything: `updateIslandLayoutForMorph` now carries TWO parameters - `t` (the shape, unchanged contract - what
the drawn size follows) and `rawT` (the animator's uncurved fraction, computed at every callsite as
currentPlayTime/duration) - and the overlay, the stretch, the samples and the haptic all consume rawT only.
Classic styles are untouched: their windows, authored against the shape all along, keep reading `t`.

The mess can now audit itself on-device: every V2 morph counts its tween frames (57 for 950 ms at 60 fps -
a count near 1 would have proven the one-shot theory the auditor saved for last) and samples
ty/sy/sx/alpha at the 20/40/60/80/100% marks of REAL time, printing them as `v2 trajectory: frames=N |@...`
at the morph's end. On a collapse the fade's own alpha shows up in the same samples - directly answering
"does the content interpolate through the collapse curve" with numbers at 76/152/228/304/380 ms.

## 17. Round 40: a log label is not a curve - but the feel it accused was real

The auditor found `ride=classic(b1378)` printed on every morph line and reasoned, sensibly, that the container
ran a legacy curve while the content pendulum ran a new one. Is stringi illumination so Go string-check run:
The label is a STATIC STRING in the beginMorphPerf trace print (service:4275 area) - it names the pill-glyph
carry's b1378 lineage, it can never change per morph because it is a literal. The container's actual curve
lives in the interpolator `morphCurve` (service:361) gated to spring for every spring style including v2, and
its values come from MotionVariant.responseFor/dampingFor per morph, with the v2 override branches. BUT - his
marrow was closer than his label: the container's damping showed 0.86, MY pick from the table-versus-constants
clash in the original spec sheet, and 0.86 costs 0.5% of the travel (~0.8 dp of overshoot, sub-visible). So
"container shows no overshoot feel" was the exact output of a choice of mine, faithfully printed, and his
binding order landed instead: 0.78 - 1.99% of travel, +6.3 px on height (~2.5 dp, the visible "2-3 px" he has
always pictured, peaked at real 176 ms, hard-caught before his 320 ms lock). The width is clamped at its
destination axis for v2 morphs (uncurbed it would dip off-card by 14 px, ghost-persist territory); height
keeps the bounce. The ride-label in the begin trace is variantized too, seeing the truth is now one grep away
instead of one theory.

## 18. What is still unproven


Nothing here has been confirmed by his eyes yet. What the frames do is arithmetic and can be checked; what it looks
like can only come from him, and the honest list of what is owed is short: whether +30 px of thickness reads as
liquid or as a card that does not fit, whether the hyper morph's tap is now distinguishable from it, whether the
glass style is still "smooth" with its blur gone, and the b1401 ride verdict.

And the methodological debt, which is the part worth keeping: this doc described the *curve* for three rounds while
the device had an *edge*. Every claim about an amplitude has to be checked against three things - the window's own
bounds, the axis the surface is free to move on, and the number of frames the panel will actually deliver - and if
any one of them says "no room", the answer is to change the axis, not to make the number bigger.
