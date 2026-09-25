package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * The rules the two outside designs asked for, tested as arithmetic.
 *
 * The first test in here is the reason the file exists. b1401 shipped a fourth morph style whose radio button
 * could not be selected: `AppSettings` clamped the stored value into the range that existed *before* the style
 * was added, so choosing option 4 stored option 3, and the tester's verdict - "4th mei kuchh to alag hai he
 * nahi, 3rd jaisa he to hai" - was a correct description of the app rather than an ungrateful one. No amount of
 * on-device tuning would ever have fixed that, and no Android-side test would have caught it either, because
 * the bug is one line of range math. So the range is now one number in one place, and this file fails the build
 * if a style escapes it.
 */
class MotionVariantTest {

    // ---------------------------------------------------------------- the reachable-range guard

    @Test
    fun everyDefinedStyleIsSelectable() {
        val styles = intArrayOf(
            AppSettings.MORPH_STYLE_BALANCED, AppSettings.MORPH_STYLE_CARRY,
            AppSettings.MORPH_STYLE_SHAPE_ONLY, AppSettings.MORPH_STYLE_GLASS,
            AppSettings.MORPH_STYLE_LIQUID, AppSettings.MORPH_STYLE_HYPERMORPH,
            AppSettings.MORPH_STYLE_LIQUIDPULL,
        )
        for (s in styles) assertEquals("style $s must survive the clamp", s, MotionVariant.clampStyle(s))
        assertTrue("MAX_STYLE must cover the last style", MotionVariant.MAX_STYLE >= styles.last())
        assertTrue("MIN_STYLE must cover the first style", MotionVariant.MIN_STYLE <= styles.first())
    }

    @Test
    fun noStyleNumberIsLeftOutOfRange() {
        // The clamp used to be written twice, in the getter and in the setter, with a literal upper bound. Both
        // now call this, so a style added without widening the bound is a test failure and not a lost round.
        val declared = listOf(
            "MORPH_STYLE_BALANCED", "MORPH_STYLE_CARRY", "MORPH_STYLE_SHAPE_ONLY",
            "MORPH_STYLE_GLASS", "MORPH_STYLE_LIQUID", "MORPH_STYLE_HYPERMORPH",
        )
        assertEquals(6, declared.size)
        for (v in -3..12) {
            val c = MotionVariant.clampStyle(v)
            assertTrue("clamp escaped: $v -> $c", c in MotionVariant.MIN_STYLE..MotionVariant.MAX_STYLE)
        }
    }

    @Test
    fun everyStyleHasItsOwnName() {
        val names = (MotionVariant.MIN_STYLE..MotionVariant.MAX_STYLE).map { MotionVariant.styleName(it) }
        assertEquals("one name per style", names.size, names.toSet().size)
        assertTrue(names[0] == "balanced")
        assertTrue(names.last().contains("aapka design"))
        // ...and the Context-free name the service logs is the same string the radio promises him.
        assertEquals(MotionVariant.styleName(AppSettings.MORPH_STYLE_HYPERMORPH), "hypermorph (ChatGPT)")
        assertEquals(MotionVariant.styleName(AppSettings.MORPH_STYLE_LIQUIDPULL), "liquid pull (aapka design)")
    }

    @Test
    fun theSpringStylesAreSpringsAndTheOldFourAreNot() {
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_BALANCED))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_CARRY))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_SHAPE_ONLY))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_GLASS))
        assertTrue(MotionVariant.isSpring(AppSettings.MORPH_STYLE_LIQUID))
        assertTrue(MotionVariant.isSpring(AppSettings.MORPH_STYLE_HYPERMORPH))
        assertTrue(MotionVariant.isSpring(AppSettings.MORPH_STYLE_LIQUIDPULL)) // the pull needs an elastic surface
    }

    // ---------------------------------------------------------------- Claude's spring

    @Test
    fun springStartsAndEndsWhereItMust() {
        val d = 460L
        assertEquals(0f, MotionVariant.spring(0f, d, 0.42f, 0.85f), 1e-6f)
        assertEquals(1f, MotionVariant.spring(1f, d, 0.42f, 0.85f), 1e-6f)
        // Out of range is a frame the animator should not have asked for, but a NaN would poison the size.
        assertEquals(0f, MotionVariant.spring(-0.4f, d, 0.42f, 0.85f), 1e-6f)
        assertEquals(1f, MotionVariant.spring(1.6f, d, 0.42f, 0.85f), 1e-6f)
    }

    @Test
    fun criticalDampingNeverOvershoots() {
        // The "no bounce on a collapse" rule, expressed as a number: if any frame of a zeta=1 curve crosses 1.0,
        // the card would puff past its own size on the way shut.
        for (i in 0..40) {
            val v = MotionVariant.spring(i / 40f, 300L, 0.28f, 1f)
            assertTrue("critical spring overshot at $i: $v", v <= 1.000001f)
            assertTrue("critical spring went backwards at $i: $v", v >= -1e-6f)
        }
        assertEquals(0f, MotionVariant.peakOvershoot(1f), 1e-6f)
    }

    @Test
    fun theTracePrintsTheTravelTheCurveActuallyAdds() {
        // The number in `[MORPH] start` is the whole reason his next log can confirm or refute this round without
        // me describing it, so it has to be the curve's own peak on the axis the motion uses - height - and not a
        // claim about a pin that no longer exists.
        for (style in intArrayOf(AppSettings.MORPH_STYLE_LIQUID, AppSettings.MORPH_STYLE_HYPERMORPH)) {
            for (profile in 0 until MotionVariant.PROFILE_COUNT) {
                val z = MotionVariant.dampingFor(style, profile, true)
                // The travel is a share of the *distance*, not of the final height - 317 px of growth, not 421 px
                // of card - because that is what `beginMorphPerf` prints and what his eye measures. And the loop is
                // liquid-only: the other style deliberately adds a settle tap on top of the curve, so its printed
                // number is the max of two things, which the signature test below covers.
                val travel = 421f - 104f
                if (style != AppSettings.MORPH_STYLE_LIQUID) continue
                val predicted = (travel * MotionVariant.peakOvershoot(z)).toInt()
                var seen = 0f
                for (k in 0..200) seen = maxOf(seen, MotionVariant.spring(k / 200f, 380L, 0.30f, z) - 1f)
                // One pixel at most, because both sides truncate: 317 x 0.0947 is 30.04 and the sampled grid
                // lands on 30.03, and float32 is entitled to put either of them under 30.0. The claim worth
                // pinning is that the log and the curve agree about what the bounce is, not their last decimal.
                val sampled = (travel * seen).toInt()
                assertTrue("profile $profile: the curve says $predicted, the trace would say $sampled",
                    kotlin.math.abs(predicted - sampled) <= 1)
            }
        }
    }

    @Test
    fun springIsMonotonicEnoughToBeReadable() {
        // Bouncy is allowed to ring, but a curve that oscillates three times in 400 ms is a wobbling card.
        var crossings = 0
        var prev = 0f
        for (i in 1..60) {
            val v = MotionVariant.spring(i / 60f, 420L, 0.3f, 0.6f)
            if ((v - 1f) * (prev - 1f) < 0f) crossings++
            prev = v
        }
        assertTrue("too many crossings: $crossings", crossings <= 2)
    }

    @Test
    fun profilesGetFasterInOrder() {
        val snappy = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SNAPPY, true, 380L)
        val silky = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true, 380L)
        val bouncy = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_BOUNCY, true, 380L)
        // Not the same ordering the response column had before: snappy is the quickest arrival, and bouncy is the
        // most patient spring because its settle is long. The profiles are separated by amplitude (the test below
        // that measures pixels), not by response - a bouncy profile with a *long* response cannot finish at all.
        assertTrue("snappy $snappy must arrive no later than silky $silky", snappy <= silky + 0.06f)
        assertTrue("bouncy $bouncy must be the most patient spring", bouncy < silky)
        assertEquals(MotionVariant.PROFILE_SNAPPY, MotionVariant.clampProfile(-9))
        assertEquals(MotionVariant.PROFILE_COUNT - 1, MotionVariant.clampProfile(99))
        assertEquals("bouncy", MotionVariant.profileName(2))
    }

    @Test
    fun closingIsQuickerThanOpeningAndDoesNotBounce() {
        // Both designs say the same thing in different words: Claude's table gives an auto-collapse zeta=1.0
        // ("a lid going down should not wobble") and ChatGPT's staged pill return is 170-210 ms against a
        // 220-280 ms bloom. If a future edit makes closing the slower, springier half, this fails.
        for (style in intArrayOf(AppSettings.MORPH_STYLE_LIQUID, AppSettings.MORPH_STYLE_HYPERMORPH)) {
            for (profile in 0 until MotionVariant.PROFILE_COUNT) {
                val open = MotionVariant.responseScaleFor(style, profile, true)
                val close = MotionVariant.responseScaleFor(style, profile, false)
                assertTrue("collapse must not be the slower half ($style/$profile: $open vs $close)", close < open)
            }
        }
        assertEquals(1f, MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, 0, false), 1e-6f)
        assertEquals(1f, MotionVariant.dampingFor(AppSettings.MORPH_STYLE_BALANCED, 2, true), 1e-6f)
        val bloom = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_HYPERMORPH, 1, true)
        assertTrue("his bloom asks for 0.82-0.9, got $bloom", bloom in 0.82f..0.9f)
    }

    // ---------------------------------------------------------------- Claude's gate

    @Test
    fun contentWaitsForTheShape() {
        assertEquals(0f, MotionVariant.gated(0.59f, 0.6f), 1e-6f)
        assertEquals(0f, MotionVariant.gated(0f, 0.6f), 1e-6f)
        assertEquals(1f, MotionVariant.gated(1f, 0.6f), 1e-6f)
        assertEquals(0.5f, MotionVariant.gated(0.8f, 0.6f), 1e-3f)
        // A gate of 0 is the old behaviour: nothing about the classic styles changes because the gate exists.
        assertEquals(0.3f, MotionVariant.gated(0.3f, 0f), 1e-6f)
        // And a gate that would leave no time for the fade is pulled back rather than dividing by zero.
        assertFalse(MotionVariant.gated(1f, 0.99f).isNaN())
        assertEquals(1f, MotionVariant.gated(1f, 0.99f), 1e-3f)
    }

    @Test
    fun cornersFollowTheHeightAndNeverExceedTheTarget() {
        assertEquals(60f, MotionVariant.tensionRadius(120f, 200f), 1e-6f)
        assertEquals(22f, MotionVariant.tensionRadius(120f, 22f), 1e-6f)
        // A capsule mid-morph is shorter, so its corners are tighter: that is the "it is being squeezed out of
        // the pill" read, and it is a consequence of the height rule rather than a second animation.
        assertTrue(MotionVariant.tensionRadius(40f, 200f) < MotionVariant.tensionRadius(120f, 200f))
    }

    @Test
    fun compressionHitsHisNumbersAndIsGoneByTheBloom() {
        val w = MotionVariant.COMPRESSION_WINDOW
        assertEquals(0.97f, MotionVariant.compressedWidth(w, w, 0.03f), 1e-4f)
        assertEquals(1.06f, MotionVariant.compressedHeight(w, w, 0.03f), 1e-4f)
        assertEquals(1f, MotionVariant.compressedWidth(0f, w, 0.03f), 1e-6f)
        assertEquals(1f, MotionVariant.compressedWidth(2f * w, w, 0.03f), 1e-6f)
        assertEquals(1f, MotionVariant.compressedWidth(0.9f, w, 0.03f), 1e-6f)
        // Off by the end of phase A, and a triangle at the join, so the release cannot step.
        assertTrue(MotionVariant.compression(w * 0.5f, w, 0.03f) > 0f)
        assertTrue(MotionVariant.compression(w * 1.5f, w, 0.03f) > 0f)
        assertEquals(1f, MotionVariant.compressedWidth(0.5f, w, 0f), 1e-6f) // slider at 0 = the old morph
    }

    @Test
    fun microSettleIsABumpThatLandsFlat() {
        val w = MotionVariant.SETTLE_WINDOW
        assertEquals(0f, MotionVariant.microSettle(0.5f, w, 0.02f), 1e-6f)
        assertEquals(0f, MotionVariant.microSettle(1f - w - 0.01f, w, 0.02f), 1e-6f)
        // It must END at zero: the size the morph lands on is the size the card has to be, and a settle that
        // leaves the box 2 % wide is a card with a wrong final width.
        assertTrue("the settle has to return the box to its size: ${MotionVariant.microSettle(1f, w, 0.02f)}",
            abs(MotionVariant.microSettle(1f, w, 0.02f)) < 1e-4f)
        assertEquals(0.02f, MotionVariant.microSettle(1f - w / 2f, w, 0.02f), 1e-4f)
        var max = 0f
        for (i in 0..200) max = max(max, MotionVariant.microSettle(i / 200f, w, 0.02f))
        assertTrue("the bump must stay a bump, not a bounce: $max", max in 0.019f..0.021f)
        assertEquals(0f, MotionVariant.microSettle(0.99f, w, 0f), 1e-6f) // slider at 0 = no pulse at all
    }

    @Test
    fun aGateRunBackwardsIsACliffNotAFade() {
        // Why the service gates the content on the way OUT and not on the way back. Composed with the collapse
        // curve, the gate leaves the fade almost no travel to happen in: the alpha is already zero while the box
        // is still most of the way open, and it gets there in one step. That step is the "jhatka" he has been
        // naming for twenty-five builds, so the composition is not allowed, and this is the proof rather than my
        // word for it.
        val g = MotionVariant.DEFAULT_GATE
        val outBy = 0.4f
        fun gatedAlpha(open: Float) = MorphCarry.contentOpen(MotionVariant.gated(open, g), false, outBy)
        fun plainAlpha(open: Float) = MorphCarry.contentOpen(open, false, outBy)
        val step = gatedAlpha(0.90f) - gatedAlpha(0.84f)
        assertTrue("expected a cliff in the gated collapse, got $step", step > 0.1f)
        assertTrue("the plain curve is a fade by comparison",
            plainAlpha(0.90f) - plainAlpha(0.84f) < step / 2f)
    }

    @Test
    fun magneticAnchorsLagThenSnap() {
        assertEquals(0f, MotionVariant.magnetic(0f, 0.6f), 1e-6f)
        assertEquals(1f, MotionVariant.magnetic(1f, 0.6f), 1e-6f)
        // A pull never puts the content ahead of where linear would have it - it holds it back and then closes
        // the gap, which is the hang-then-snap. So the test is on the two things that are true about that: the
        // curve is always behind the diagonal, and its speed at the end is above its speed at the start.
        val pull = 1f
        for (i in 1..9) {
            val p = i / 10f
            assertTrue("pull ran ahead of linear at $p", MotionVariant.magnetic(p, pull) <= p + 1e-6f)
        }
        assertTrue(MotionVariant.magnetic(0.5f, pull) < 0.5f)
        val early = MotionVariant.magnetic(0.25f, pull) - MotionVariant.magnetic(0.05f, pull)
        val late = MotionVariant.magnetic(0.95f, pull) - MotionVariant.magnetic(0.75f, pull)
        assertTrue("no snap: late $late must beat early $early", late > early * 1.5f)
        // 0 pull must be exactly the behaviour he has already judged, not an approximation of it.
        for (t in floatArrayOf(0.1f, 0.37f, 0.5f, 0.8f)) {
            assertEquals(t, MotionVariant.magnetic(t, 0f), 1e-6f)
        }
    }

    @Test
    fun rippleIsFeltAndNotSeen() {
        val w = MotionVariant.RIPPLE_WINDOW
        assertEquals(0f, MotionVariant.ripple(0f, w), 1e-6f)
        assertEquals(0f, MotionVariant.ripple(w, w), 1e-6f)
        assertEquals(0f, MotionVariant.ripple(w * 2f, w), 1e-6f)
        val peak = MotionVariant.ripple(w / 2f, w)
        assertEquals(1f, peak, 1e-3f)
        for (i in 0..30) {
            val v = MotionVariant.ripple(i / 30f, w)
            assertTrue("ripple escaped 0..1: $v", v in 0f..1.0001f)
        }
        // 40-70 ms of a ~250 ms bloom: the window is a fraction, and it must stay a fraction.
        assertTrue(w in 0.12f..0.32f)
    }

    @Test
    fun progressFollowsTheAxisThatMoves() {
        assertEquals(0.5f, MotionVariant.progressOf(100, 0, 300, 0, 200, 0), 1e-6f)
        assertEquals(0.5f, MotionVariant.progressOf(100, 60, 100, 160, 100, 110), 1e-6f)
        // A spring past the target reports progress past 1 - the caller clamps it - and a zero span is a
        // finished morph, never a division by zero.
        assertEquals(1.25f, MotionVariant.progressOf(100, 0, 300, 0, 350, 0), 1e-6f)
        assertEquals(1f, MotionVariant.progressOf(100, 0, 100, 0, 100, 0), 1e-6f)
    }

    @Test
    fun theShapeArrivesByMidWindowSoTheTailIsRealMotion() {
        // b1411's second defect, and the one his "jaisa bata rhe the waisa kuch ho he nahi raha" was really
        // about: the response table was derived from the window, but a spring's rise is fast relative to its own
        // response, so the box was at 99 % of its size in five frames and spent the remaining eighteen drifting
        // by a dozen pixels. Motion he could not see is motion that did not happen. So the arrival and the settle
        // are now both asserted to occupy the window. Claude's own acceptance line is "reaches 90 % in ~1.25x
        // response", which read against the frames this app runs at means: at frame 5.5 a tenth of the trip is
        // still to go, and by frame 11 the box is there. Under the first bar the shape snaps open in five frames
        // and the remaining eighteen are a few pixels of drift - which is how b1411 came to look like nothing.
        for (profile in 0 until MotionVariant.PROFILE_COUNT) {
            val r = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, profile, true, 380L)
            val z = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, profile, true)
            val early = MotionVariant.spring(0.25f, 380L, r, z)
            val mid = MotionVariant.spring(0.5f, 380L, r, z)
            assertTrue("profile $profile snaps open before frame 6 ($early)", early <= 0.90f)
            assertTrue("profile $profile is still crawling at half the window ($mid)", mid >= 0.90f)
        }
        // A collapse must NOT do this: Claude's table says a lid going down gets no bounce and no ceremony.
        val rc = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, false, 340L)
        val zc = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, false)
        assertEquals(1f, zc, 1e-6f)
        assertTrue("collapse must be done by 60 % of the window", MotionVariant.spring(0.6f, 340L, rc, zc) > 0.97f)
    }

    @Test
    fun everyEnvelopeIsLongEnoughToShowAtSixtyHertz() {
        // The windows are on the clock now precisely because the shape's progress is front-loaded. At the shortest
        // morph the app runs, every one of these must last at least four frames - under that, a "feel" is a
        // dropped frame and his eye files it as a glitch, which is exactly what b1411's single blurred frame was.
        val frameMs = 16.7f
        for (win in floatArrayOf(
            MotionVariant.COMPRESSION_WINDOW * 2f,
            MotionVariant.RIPPLE_WINDOW,
            MotionVariant.SETTLE_WINDOW,
        )) {
            val frames = win * MotionVariant.MIN_MORPH_WINDOW_MS / frameMs
            assertTrue("a $win window is $frames frames at 60 Hz", frames >= 4f)
            assertTrue("and must still be a flash, not a phase: $frames", frames <= 9f)
        }
        // And they are read off the clock, so the spring's front-loading cannot squash them (the regression in
        // one line): at one frame in, the clock is 0.045 while the shape is already at 0.11.
        assertTrue(MotionVariant.compression(0.12f, MotionVariant.COMPRESSION_WINDOW, 0.05f) > 0.02f)
    }



    // ---------------------------------------------------------------- b1406's three glitches



    @Test
    fun everySpringFitsInsideTheWindowItIsGiven() {
        // The silent killer from b1406: a response that does not fit its window means the spring never reaches its
        // target while the animation is running, the interpolator pins the last frame to 1.0, and a "dual-spring
        // liquid capsule" becomes a slightly different ease. "dono new options ek he hai" was that truncation.
        // Measured on the real curve rather than against a formula, so it cannot drift out of sync with spring().
        for (style in intArrayOf(AppSettings.MORPH_STYLE_LIQUID, AppSettings.MORPH_STYLE_HYPERMORPH)) {
            for (profile in 0 until MotionVariant.PROFILE_COUNT) {
                for (towardCard in booleanArrayOf(true, false)) {
                    val window = if (towardCard) 380L else 340L
                    val response = MotionVariant.responseFor(style, profile, towardCard, window)
                    val zeta = MotionVariant.dampingFor(style, profile, towardCard)
                    val atTheEnd = MotionVariant.spring(0.98f, window, response, zeta)
                    assertTrue(
                        "style $style profile $profile towardCard=$towardCard sits at $atTheEnd at 98 % of its " +
                            "window - the spring is being cut off, which is the bug that hid both designs",
                        kotlin.math.abs(1f - atTheEnd) < 0.01f,
                    )
                }
            }
        }
    }

    @Test
    fun theTwoSignaturesAreBigEnoughToSeeOnTheAxisHeCanWatch() {
        // The yardstick changed with the axis, and the bars had to follow or the test would have been theatre: the
        // card is 1067 px wide but only 421 px tall, so a ratio that overshot 11 px sideways overshoots 4 px down.
        // These are the ratios that buy 0 / 8 / 40 px of *vertical* travel on his panel at 120 Hz, which is where
        // "you can see it" starts to live for a 380 ms morph.
        // Same basis the trace prints: the overshoot is a share of the 104 -> 421 growth, because a taller card is
        // the whole motion and the 421 it lands on is not motion at all.
        val cardH = 421f - 104f
        fun travel(profile: Int) = cardH * MotionVariant.peakOvershoot(
            MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, profile, true)
        )
        val snappy = travel(MotionVariant.PROFILE_SNAPPY)
        val silky = travel(MotionVariant.PROFILE_SILKY)
        val bouncy = travel(MotionVariant.PROFILE_BOUNCY)
        assertTrue("snappy must not bounce at all on a lid going either way: $snappy", snappy <= 3f)
        assertTrue("silky's settle is only $silky px of thickness", silky >= 6f)
        assertTrue("bouncy must be at least twice silky, got $bouncy vs $silky", bouncy >= 2f * silky)
        assertTrue("and bouncy has to be a visible thickness change: $bouncy", bouncy >= 25f)
        // The width, meanwhile, must never move past the target at all - that is the axis rule, not a tuning.
        for (profile in 0 until MotionVariant.PROFILE_COUNT) {
            assertEquals("width may not overshoot: profile $profile", 1067,
                MotionVariant.axisWidth(1157, 1067, growing = true))
            assertEquals("and may not undershoot on the way back", 366,
                MotionVariant.axisWidth(355, 366, growing = false))
        }
    }

    @Test
    fun thePullIsOneArcSharedBySinkAndStretch() {
        // His steps 1-3 as testable properties: one driver, zero at both ends (the catch leaves nothing
        // behind), the peak exactly half-way into the window, a single hump on each side of it.
        assertEquals(0f, MotionVariant.pullPhase(0f), 1e-6f)
        assertEquals(0f, MotionVariant.pullPhase(MotionVariant.PULL_WINDOW), 1e-6f)
        assertEquals(0f, MotionVariant.pullPhase(0.999f), 1e-6f)
        assertEquals(1f, MotionVariant.pullPhase(MotionVariant.PULL_WINDOW / 2f), 1e-4f)
        assertTrue("a hump, left side", MotionVariant.pullPhase(0.2f) < MotionVariant.pullPhase(0.35f))
        assertTrue("a hump, right side", MotionVariant.pullPhase(0.55f) > MotionVariant.pullPhase(0.7f))
        assertEquals(1f + MotionVariant.STRETCH_Y_MAX, MotionVariant.stretchScaleY(1f), 1e-6f)
        assertEquals(1f - MotionVariant.SQUEEZE_X, MotionVariant.stretchScaleX(1f), 1e-6f)
        assertEquals(1f, MotionVariant.stretchScaleY(0f), 1e-6f)
        assertEquals(1f, MotionVariant.stretchScaleX(0f), 1e-6f)
    }

    @Test
    fun theFloatKeepsTheHierarchyHeWrote() {
        // Step 4 verbatim: text bobs more than the icon, the icon at least a pixel (or it is not there), the
        // wave is one sine that never exceeds the amplitude, and a full period later it is back to zero.
        assertTrue(MotionVariant.FLOAT_TEXT_PX > MotionVariant.FLOAT_ICON_PX)
        assertTrue(MotionVariant.FLOAT_ICON_PX >= 1f)
        var extreme = 0f
        for (i in 0..100) {
            val v = MotionVariant.floatOffsetPx(i / 100f, MotionVariant.FLOAT_TEXT_PX)
            extreme = maxOf(extreme, kotlin.math.abs(v))
        }
        assertTrue(extreme <= MotionVariant.FLOAT_TEXT_PX + 1e-3f)
        assertEquals(0f, MotionVariant.floatOffsetPx(0f, MotionVariant.FLOAT_TEXT_PX), 1e-4f)
        assertEquals(0f, MotionVariant.floatOffsetPx(0.5f, MotionVariant.FLOAT_TEXT_PX), 1e-4f)
    }

    @Test
    fun buoyancyIsALagAndADipInsideExactEndings() {
        // The round-33 ask, "buoyancy wala effect, jaise content liquid mein hai": the content's ride progress
        // on the spring styles is its own slower, underdamped spring. Two properties make it safe on every
        // expand of those styles: the endpoints are EXACT (the shared spring clamps them), so the grid's
        // hand-off at open = 1 still lands on the host's own offset - no snap, ever - and the overshoot is
        // bounded small enough that the dip reads as a float, not as a second bounce fighting the box's.
        val buoys = (0..46).map { i -> MotionVariant.buoy(i / 46f, 380L, 0.15f) }   // his saved profile: snappy
        assertEquals(0f, buoys.first(), 1e-6f)
        assertEquals(1f, buoys.last(), 1e-6f)
        val peak = buoys.max()
        assertTrue("a buoyant rise must overshoot or it is invisible: peak=$peak", peak > 1.03f)
        assertTrue("and must stay a float, not a second spring of the box's size: peak=$peak", peak < 1.15f)
        // After the dip it comes home and stays: the last sixth of the morph is inside +/- 1 % of the rest.
        val settled = buoys.drop(38)
        assertTrue("it must come home: $settled", settled.all { kotlin.math.abs(1f - it) < 0.01f })
        // On the way up it is travelling (mid-liquid) well before it dips: a frame ~15 % in is past half but
        // not past 1 - it has not arrived early, which would be latency, nor teleported, which was the old
        // "content sidha aa jata hai" once the profile went critically damped.
        val early = buoys[7]
        assertTrue("t=15%% in the liquid: $early", early in 0.5f..1.0f)
        // The dip is real and bounded, and the idol frame never sits above it by chance of sampling.
        assertTrue("the dip is where the rise ends: $early < $peak", early < peak)
        // Every spring profile gets the same exact endings, whatever the base response.
        for (response in listOf(0.15f, 0.26f, 0.29f)) {
            assertEquals(1f, MotionVariant.buoy(1f, 380L, response), 1e-6f)
            assertEquals(0f, MotionVariant.buoy(0f, 380L, response), 1e-6f)
        }
    }

    // ---------------------------------------------------------------- the shared clamps

    @Test
    fun slidersCannotSmuggleInNonsense() {
        assertEquals(0, MotionVariant.clampPct(-10, 0, 90))
        assertEquals(90, MotionVariant.clampPct(10_000, 0, 90))
        assertEquals(45, MotionVariant.clampPct(45, 0, 90))
        assertNotEquals(MotionVariant.clampPct(5, 0, 8), MotionVariant.clampPct(105, 0, 8))
    }

    @Test
    fun aFrameOfTheLiquidMorphLooksLikeTheDesign() {
        // A miniature of what the service does per frame: 260 ms of spring at damping 0.85, a 120 px box
        // opening to 900 px, corners from the height, content behind the gate. If any of these four numbers ever
        // stop agreeing with each other, this fails - which is the only way a "feel" can be regression-tested.
        val dur = 260L
        val from = 120
        val to = 900
        val gate = 0.6f
        val zeta = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true)
        val p = MotionVariant.spring(0.5f, dur, MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true, dur), zeta)
        val w = from + ((to - from) * p).toInt()
        assertTrue("mid-morph the box must be past the start and can pass the target: $w", w > from)
        // The gate is a rule about the box, so the test is too: before it, nothing; the frame it crosses,
        // something; and the frame the box lands, everything. Spring curve included, because the point of
        // this style is that the two are not independent.
        assertEquals("nothing at 45 % of the width", 0f, MotionVariant.gated(0.45f, gate), 1e-6f)
        assertEquals("nothing at 60 % either", 0f, MotionVariant.gated(0.60f, gate), 1e-6f)
        assertTrue("then it has to be moving", MotionVariant.gated(0.61f, gate) > 0f)
        assertEquals("and it is fully in when the box lands", 1f, MotionVariant.gated(1f, gate), 1e-6f)
        val h = 60 + ((160 - 60) * p).toInt()
        assertEquals("corners track the height, not a curve of their own", h / 2f,
            MotionVariant.tensionRadius(h.toFloat(), 200f), 1e-4f)
    }
}
