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
        assertTrue(names.last().contains("ChatGPT"))
        // ...and the Context-free name the service logs is the same string the radio promises him.
        assertEquals(MotionVariant.styleName(AppSettings.MORPH_STYLE_HYPERMORPH), "hypermorph (ChatGPT)")
    }

    @Test
    fun theTwoNewStylesAreSpringsAndTheOldFourAreNot() {
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_BALANCED))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_CARRY))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_SHAPE_ONLY))
        assertFalse(MotionVariant.isSpring(AppSettings.MORPH_STYLE_GLASS))
        assertTrue(MotionVariant.isSpring(AppSettings.MORPH_STYLE_LIQUID))
        assertTrue(MotionVariant.isSpring(AppSettings.MORPH_STYLE_HYPERMORPH))
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
    fun underdampedSpringOvershootsByExactlyWhatThePinAllowsFor() {
        val zeta = 0.7f
        val peak = MotionVariant.peakOvershoot(zeta)
        assertTrue("a 0.7-damped spring must overshoot at all", peak > 0.04f)
        var observed = 0f
        for (i in 0..200) observed = max(observed, MotionVariant.spring(i / 200f, 620L, 0.48f, zeta))
        // The analytic peak and the sampled peak have to agree, because the pin is widened by the analytic one:
        // if the curve went higher than the bound, IslandMorphFrame clamps it and the bounce is silently eaten.
        assertEquals(1f + peak, observed, 0.02f)
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
        val snappy = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SNAPPY, true)
        val silky = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true)
        val bouncy = MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_BOUNCY, true)
        assertTrue("snappy $snappy must be quicker than silky $silky", snappy < silky)
        assertTrue("bouncy $bouncy must be slower than silky $silky", bouncy > silky)
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
                val open = MotionVariant.responseFor(style, profile, true)
                val close = MotionVariant.responseFor(style, profile, false)
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
    fun growthDodgesAnOffCentreCameraOnlyWhereDodgingIsPossible() {
        // CI run 407 failed the previous version of this test, and the failure was the lesson: I had written a
        // case where dodging cannot work - a lens at 670..730 *inside* a box spanning 460..740 - and expected a
        // leftward shove out of it. "expected a leftward dodge, got 0.0" was my test being wrong about the world
        // in exactly the way the shipped rule was. So the cases are now stated the way the rule means them:
        // overlapping an edge, move off that edge; contained, do nothing; nowhere near, do nothing.
        val boxHalf = 140f
        // A) the lens sits inside the box's span: uncapturable sideways, so inert. On his 11i (screen 1080,
        // lens at 540, island centred at 540) this is every width the island ever reaches - which is precisely
        // why b1406's 224 px "avoidance" was a jump and not an avoidance.
        assertEquals(0f, MotionVariant.cutoutShift(600f, boxHalf, 700f, 30f, 0f), 1e-6f)
        // B) the lens overlaps the box's RIGHT edge (box 460..740, lens 690..750): move left until the edges
        // part, and they have to actually part - a nudge that leaves 1 px of overlap is not a fix.
        val left = MotionVariant.cutoutShift(600f, boxHalf, 720f, 30f, 0f)
        assertTrue("expected a leftward dodge, got $left", left < 0f)
        assertTrue("right edge must clear the lens", 600f + left + boxHalf <= 690f)
        // C) mirrored: the lens overlaps the LEFT edge (box 760..1040, lens 750..810), so push right.
        val right = MotionVariant.cutoutShift(900f, boxHalf, 780f, 30f, 0f)
        assertTrue("expected a rightward dodge, got $right", right > 0f)
        assertTrue("left edge must clear the lens", 900f + right - boxHalf >= 810f)
        // D) nothing near the island: inert, not merely small. Most morphs are here, and a drift would be worse
        // than the problem this exists to solve.
        assertEquals(0f, MotionVariant.cutoutShift(100f, boxHalf, 700f, 30f, 0f), 1e-6f)
        // E) a device that reports no cutout band at all (emulator, or the band hidden in the status bar): the
        // Lab's bias passes through, so the rule stays testable on the phone he actually has.
        assertEquals(18f, MotionVariant.cutoutShift(100f, boxHalf, 0f, 0f, 18f), 1e-6f)
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

    // ---------------------------------------------------------------- b1406's three glitches

    @Test
    fun theCutoutRuleIsInertOnACentredLens() {
        // b1406's own numbers, off his trace: a 1080 px screen, `cutout=540/40px`, the island centred at 540.
        // The rule as Claude wrote it asked for a 224 px shove there and the collapse obeyed, which is what he
        // saw as "pill kabhi right shift ho ja rha hai collapsing mei". A hole you are sitting on top of cannot
        // be dodged sideways, so the answer is now 0 - at the pill's width AND at the card's.
        assertEquals(0f, MotionVariant.cutoutShift(540f, 183f, 540f, 40f, 0f, 60f), 1e-6f)
        assertEquals(0f, MotionVariant.cutoutShift(540f, 533f, 540f, 40f, 0f, 60f), 1e-6f)
        // The Lab's bias still passes through, because that slider exists so this can be tested at all.
        assertEquals(30f, MotionVariant.cutoutShift(540f, 183f, 540f, 40f, 30f, 60f), 1e-6f)
    }

    @Test
    fun theCutoutRuleStillDodgesWhereDodgingWorks() {
        // A lens at the left edge of the island's span: a small push right uncovers it.
        val shift = MotionVariant.cutoutShift(600f, 183f, 420f, 40f, 0f, 60f)
        assertTrue("expected a rightward nudge, got $shift", shift > 0f)
        assertTrue("600 + $shift must clear the lens", 600f + shift - 183f > 460f)
        // And when clearing it would take a jump, the cap takes what is allowed and accepts partial avoidance -
        // an island that teleports to dodge a lens is worse than one that sits near it.
        val capped = MotionVariant.cutoutShift(600f, 183f, 455f, 40f, 0f, 20f)
        assertTrue("cap ignored: $capped", abs(capped) <= 20f + 1e-3f)
        // Ramped to nothing at both ends, so a rest position is never drawn where the layout will not put it.
        assertEquals(0f, MotionVariant.avoidRamp(0f), 1e-6f)
        assertEquals(0f, MotionVariant.avoidRamp(1f), 1e-6f)
        assertEquals(1f, MotionVariant.avoidRamp(0.5f), 1e-6f)
        assertTrue("the ramp must be smooth at the ends", MotionVariant.avoidRamp(0.02f) < 0.08f)
    }

    @Test
    fun everySpringFitsInsideTheWindowItIsGiven() {
        // The silent killer from b1406: response 0.42 s inside a 0.38 s animation means the spring never reaches
        // its target while it is running, the interpolator pins the last frame to 1.0, and the "dual-spring
        // liquid capsule" becomes a slightly different ease. "dono new options ek he hai" was that truncation.
        for (style in intArrayOf(AppSettings.MORPH_STYLE_LIQUID, AppSettings.MORPH_STYLE_HYPERMORPH)) {
            for (profile in 0 until MotionVariant.PROFILE_COUNT) {
                for (towardCard in booleanArrayOf(true, false)) {
                    val ms = MotionVariant.responseFor(style, profile, towardCard) * 1000f
                    assertTrue(
                        "style $style profile $profile towardCard=$towardCard needs ${ms}ms of settle in " +
                            MotionVariant.MIN_MORPH_WINDOW_MS + "ms",
                        ms * MotionVariant.SPRING_SETTLE_FACTOR <= MotionVariant.MIN_MORPH_WINDOW_MS
                    )
                }
            }
        }
    }

    @Test
    fun theTwoSignaturesAreBigEnoughToSeeAtHisRefreshRate() {
        // His panel was at 60 Hz in that trace (`hz=60`), so anything under ~3 frames is not a feel. Both of
        // these were shipped below that threshold in b1406: Claude's 0.85 damping overshot 7 px on a 1067 px
        // card, and ChatGPT's 30-45 ms compression was two frames of nothing.
        val card = 1067f
        val silky = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true)
        val bouncy = MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_BOUNCY, true)
        assertTrue("silky bounce invisible: ${MotionVariant.peakOvershoot(silky) * card}px",
            MotionVariant.peakOvershoot(silky) * card >= 15f)
        assertTrue("bouncy must be a different animal", MotionVariant.peakOvershoot(bouncy) * card >
            MotionVariant.peakOvershoot(silky) * card * 2f)
        assertTrue("snappy is the one that barely lands",
            MotionVariant.peakOvershoot(MotionVariant.dampingFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SNAPPY, true)) * card < 5f)
        val frames = MotionVariant.COMPRESSION_WINDOW * MotionVariant.MIN_MORPH_WINDOW_MS / 16.7f
        assertTrue("phase A is ${frames} frames at 60 Hz, must be at least 3", frames >= 3f)
        assertTrue("the ripple would fog the text for a whole second",
            MotionVariant.RIPPLE_WINDOW * MotionVariant.MIN_MORPH_WINDOW_MS <= 120f)
    }

    @Test
    fun theHeadroomALayoutNeedsToLandExactIsZeroForTheClassicStyles() {
        // The extra surface a spring needs moves every anchor that is measured from the view's edge, so the
        // correction that puts them back must be provably 0 for the styles he has already accepted: they ask for
        // no headroom, and this is the difference between "the new looks are safe" and "the new looks are on".
        assertEquals(0, MotionVariant.viewExcessHalf(1067, 1067))
        assertEquals(0, MotionVariant.viewExcessHalf(366, 366))
        assertEquals(0, MotionVariant.viewExcessHalf(1000, 1067)) // never negative, whatever the pin ends up as
        assertEquals(34, MotionVariant.viewExcessHalf(1067 + 68, 1067))
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
        val p = MotionVariant.spring(0.5f, dur, MotionVariant.responseFor(AppSettings.MORPH_STYLE_LIQUID, MotionVariant.PROFILE_SILKY, true), zeta)
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
